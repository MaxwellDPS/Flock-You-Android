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
import com.flockyou.detection.framework.TrackerDatabase
import com.flockyou.detection.framework.UltrasonicTrackingPurpose
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
class UltrasonicDetector(
    private val context: Context,
    private val errorCallback: ScanningService.DetectorCallback? = null
) {

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
        private const val DETECTION_THRESHOLD_DB = 30.0 // Signal must be this many dB above noise floor (increased to reduce FPs)
        private const val BEACON_DURATION_THRESHOLD_MS = 5_000L // Must persist for 5 seconds (true beacons are persistent)
        private const val MIN_DETECTIONS_TO_CONFIRM = 5 // Require multiple detections before showing in UI
        private const val ANOMALY_COOLDOWN_MS = 60_000L // 1 minute between alerts

        // Tracking likelihood thresholds for alert severity
        private const val TRACKING_LIKELIHOOD_HIGH = 80f    // HIGH alert - definite tracking beacon
        private const val TRACKING_LIKELIHOOD_MEDIUM = 60f  // MEDIUM alert - likely tracking
        private const val TRACKING_LIKELIHOOD_LOW = 40f     // LOW alert - possible, needs monitoring
        // Below 40% = INFO only, don't create anomaly record

        // False positive reduction thresholds
        private const val FREQUENCY_STABILITY_TOLERANCE_HZ = 10 // True beacons are precise (+/-10Hz)
        private const val AMPLITUDE_STABILITY_THRESHOLD = 3.0   // dB variance threshold for steady signal
        private const val MIN_PERSISTENCE_FOR_ALERT_MS = 5_000L // Must persist 5+ seconds
        private const val MAX_CONCURRENT_BEACONS_FOR_NOISE = 4  // If 5+ detected, likely noise burst
        // Default timing values (can be overridden by updateScanTiming)
        private const val DEFAULT_SCAN_DURATION_MS = 5_000L // 5 second scan windows
        private const val DEFAULT_SCAN_INTERVAL_MS = 20_000L // Scan every 20 seconds (more frequent)

        // Frequency tolerance for matching (Hz)
        private const val FREQUENCY_TOLERANCE = 100

        /**
         * Get all known beacon frequencies from the unified tracker database
         * This provides expanded coverage of ultrasonic tracking technologies
         */
        fun getKnownBeaconFrequencies(): List<Int> {
            return TrackerDatabase.getAllUltrasonicFrequencies()
        }
    }

    // Use expanded frequency database
    private val knownBeaconFrequencies: List<Int>
        get() = TrackerDatabase.getAllUltrasonicFrequencies()

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var isMonitoring = false
    private var detectorJob: Job? = null
    private val detectorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Error tracking for graceful restart
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3

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
        var locationHistory: MutableList<LocationEntry> = mutableListOf(),
        // Frequency stability tracking (true beacons are precise +/-10Hz)
        var frequencyHistory: MutableList<Int> = mutableListOf(),
        // Environmental context
        var environmentalContext: EnvironmentalContext = EnvironmentalContext.UNKNOWN,
        // Cross-location tracking (same beacon at multiple user locations = suspicious)
        var seenAtHomeLocation: Boolean = false,
        var seenAtOtherLocations: Int = 0
    )

    data class LocationEntry(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double
    )

    /**
     * Environmental context for threat assessment
     */
    enum class EnvironmentalContext(val displayName: String, val baseThreatMultiplier: Float) {
        HOME("Home Location", 0.5f),           // Your own devices - lower threat
        WORK("Work Location", 0.6f),           // Expected beacons - moderate
        RETAIL("Retail Store", 0.7f),          // Expect beacons - moderate unless following
        OUTDOOR_RANDOM("Random Outdoor", 1.2f), // Higher threat - unexpected
        UNKNOWN("Unknown Location", 1.0f)       // Default multiplier
    }

    /**
     * False positive source categories
     *
     * Real-world sources of ultrasonic frequencies that are NOT tracking beacons:
     * - CRT monitors/TVs: 15.75 kHz horizontal scan (fading out)
     * - LCD backlight PWM: 20-25 kHz range
     * - Switching power supplies: 20-100 kHz
     * - HVAC ultrasonic humidifiers: 20+ kHz
     * - Dog/pest deterrents: 18-25 kHz
     * - Older hard drives: High-frequency whine
     * - Tinnitus: User may perceive sounds that aren't there
     */
    enum class FalsePositiveSource(val displayName: String, val frequencyRanges: List<IntRange>, val description: String) {
        CRT_MONITOR(
            "CRT Monitor/TV (15.75 kHz)",
            listOf(15700..15800),
            "CRT horizontal scan at 15.75 kHz. Fading out as CRTs become rare."
        ),
        LCD_BACKLIGHT(
            "LCD Backlight PWM",
            listOf(20000..25000),
            "LCD backlight pulse-width modulation can generate ultrasonic frequencies."
        ),
        SWITCHING_POWER(
            "Switching Power Supply",
            listOf(20000..100000),
            "Laptop chargers, USB adapters, and other switching power supplies emit ultrasonic noise."
        ),
        HVAC_HUMIDIFIER(
            "HVAC Ultrasonic Humidifier",
            listOf(20000..25000),
            "Ultrasonic humidifiers in HVAC systems generate constant ultrasonic frequencies."
        ),
        DOG_PEST_DETERRENT(
            "Dog/Pest Deterrent",
            listOf(18000..25000),
            "Ultrasonic pest/dog deterrent devices emit continuous or pulsed tones in this range."
        ),
        HARD_DRIVE(
            "Hard Drive (older)",
            listOf(17000..20000),
            "Older mechanical hard drives can produce high-frequency whine."
        ),
        FLUORESCENT_LIGHTS(
            "Fluorescent Lights",
            listOf(20000..40000),
            "Fluorescent light ballasts can generate ultrasonic frequencies."
        ),
        ELECTRONICS(
            "Electronics (TV/Monitor)",
            listOf(17500..18500, 19800..20200),
            "General electronics including TVs and monitors may emit spurious ultrasonic frequencies."
        ),
        EV_PEDESTRIAN_WARNING(
            "EV/Vehicle Pedestrian Warning (AVAS)",
            listOf(17000..20000),
            "Electric vehicles emit ultrasonic tones when reversing or at low speeds to warn pedestrians. " +
            "Characterized by brief duration (seconds), rising-then-falling amplitude as vehicle passes, " +
            "and outdoor detection. Common in modern EVs, hybrids, and delivery vehicles."
        ),
        NATURAL(
            "Natural Sources (keys, whistles)",
            listOf(17500..22000),
            "Metal keys jingling, dog whistles, and other natural sources can produce brief ultrasonic bursts."
        )
    }

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
     *
     * Real-world ultrasonic tracking systems:
     * - SilverPush (India): ~18 kHz, FSK modulation, 2-5 second beacons
     * - Alphonso (US): ~18.5 kHz, PSK modulation, always-on ACR
     * - LISNR (US): ~19.5 kHz, CHIRP modulation, higher bandwidth
     * - Shopkick: ~20 kHz, retail presence in Target, Macy's, Best Buy
     * - Signal360: ~19 kHz, malls and airports
     * - Samba TV / Inscape: ~20-21 kHz, built into Samsung, Vizio, LG smart TVs
     */
    enum class KnownBeaconType(val displayName: String, val company: String, val frequencyHz: Int, val description: String) {
        SILVERPUSH(
            "SilverPush",
            "SilverPush Technologies (India)",
            18000,
            "Cross-device ad tracking. SDK was in 200+ apps (2015-2017). FSK modulation, 2-5 second beacons."
        ),
        ALPHONSO(
            "Alphonso",
            "Alphonso Inc (US)",
            18500,
            "Automated Content Recognition. Found in 1,000+ apps. Always-on background listening. FTC investigated 2018."
        ),
        SIGNAL360(
            "Signal360",
            "Signal360 (US)",
            19000,
            "Location-based advertising. Deployed in malls and airports."
        ),
        LISNR(
            "LISNR",
            "LISNR Inc (US)",
            19500,
            "Ultrasonic data transfer. Higher bandwidth. Legitimate uses (payments, ticketing) but also enables tracking."
        ),
        SHOPKICK(
            "Shopkick",
            "Shopkick/SK Telecom",
            20000,
            "Retail presence detection. Deployed in Target, Macy's, Best Buy, Walmart, CVS. Usually opt-in for rewards."
        ),
        SAMBA_TV(
            "Samba TV / Inscape",
            "Samba TV / Vizio",
            20200,
            "Smart TV ACR. Built into Samsung, Vizio, LG, Sony smart TVs. Tracks everything you watch including streaming, cable, gaming."
        ),
        ZAPR(
            "Zapr",
            "Zapr Media Labs (India)",
            17500,
            "TV content recognition for targeted advertising."
        ),
        TVISION(
            "TVision",
            "TVision Insights",
            19800,
            "TV viewership measurement. Tracks what you watch and for how long."
        ),
        UNKNOWN(
            "Unknown",
            "Unknown Source",
            0,
            "Unknown ultrasonic source. May be tracking or environmental noise."
        )
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
        val environmentalContext: EnvironmentalContext = EnvironmentalContext.UNKNOWN,

        // Risk Assessment
        val trackingLikelihood: Float,            // 0-100%
        val riskIndicators: List<String>,

        // False Positive Heuristics
        val falsePositiveLikelihood: Float = 0f,  // 0-100%
        val fpIndicators: List<String> = emptyList(),
        val concurrentBeaconCount: Int = 0,       // How many beacons detected at same time
        val isLikelyAmbientNoise: Boolean = false,
        val isLikelyDeviceArtifact: Boolean = false,

        // Frequency Stability Analysis (true beacons are stable +/-10Hz)
        val frequencyStabilityHz: Float = 0f,     // Standard deviation of frequency readings
        val isFrequencyStable: Boolean = false,   // True if stable within tolerance

        // Modulation Pattern Analysis
        val hasKnownModulationPattern: Boolean = false,
        val modulationPatternType: String? = null,

        // Enriched Description Fields
        val probableSourceDescription: String = "",
        val whatItDoes: String = "",
        val recommendedAction: String = "",
        val isFollowingAcrossLocations: Boolean = false
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
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_ULTRASONIC,
                "Missing RECORD_AUDIO permission",
                recoverable = false
            )
            return
        }

        isMonitoring = true
        consecutiveFailures = 0
        Log.d(TAG, "Starting ultrasonic beacon detection")

        addTimelineEvent(
            type = UltrasonicEventType.MONITORING_STARTED,
            title = "Ultrasonic Detection Started",
            description = "Monitoring for tracking beacons (18-22 kHz)"
        )

        errorCallback?.onDetectorStarted(ScanningService.DetectorHealthStatus.DETECTOR_ULTRASONIC)

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

        errorCallback?.onDetectorStopped(ScanningService.DetectorHealthStatus.DETECTOR_ULTRASONIC)
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
                    consecutiveFailures++
                    errorCallback?.onError(
                        ScanningService.DetectorHealthStatus.DETECTOR_ULTRASONIC,
                        "AudioRecord failed to initialize (attempt $consecutiveFailures)",
                        recoverable = consecutiveFailures < maxConsecutiveFailures
                    )
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
                            if (bin.isUltrasonic && (bin.amplitudeDb - noiseFloorDb) > DETECTION_THRESHOLD_DB) {
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

                // Report successful scan
                consecutiveFailures = 0
                errorCallback?.onScanSuccess(ScanningService.DetectorHealthStatus.DETECTOR_ULTRASONIC)

            } catch (e: Exception) {
                Log.e(TAG, "Error during ultrasonic scan", e)
                consecutiveFailures++
                errorCallback?.onError(
                    ScanningService.DetectorHealthStatus.DETECTOR_ULTRASONIC,
                    "Scan error: ${e.message ?: "Unknown error"} (attempt $consecutiveFailures)",
                    recoverable = consecutiveFailures < maxConsecutiveFailures
                )
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

            // Look up signature from unified tracker database
            val matchedSignature = TrackerDatabase.findUltrasonicByFrequency(freq, FREQUENCY_TOLERANCE)

            // Check if this matches known beacon frequencies
            val isKnownBeacon = matchedSignature != null

            // Determine source type from signature or fallback to legacy matching
            val possibleSource = matchedSignature?.let {
                "${it.manufacturer} (${it.trackingPurpose.displayName})"
            } ?: when {
                abs(freq - 18000) <= FREQUENCY_TOLERANCE -> "SilverPush/Ad Tracking"
                abs(freq - 18500) <= FREQUENCY_TOLERANCE -> "Alphonso/TV Tracking"
                abs(freq - 17500) <= FREQUENCY_TOLERANCE -> "Zapr/TV Attribution"
                abs(freq - 19000) <= FREQUENCY_TOLERANCE -> "Signal360/Advertising"
                abs(freq - 19200) <= FREQUENCY_TOLERANCE -> "Realeyes/Attention"
                abs(freq - 19500) <= FREQUENCY_TOLERANCE -> "LISNR/Cross-Device"
                abs(freq - 19800) <= FREQUENCY_TOLERANCE -> "TVision/Viewership"
                abs(freq - 20000) <= FREQUENCY_TOLERANCE -> "Shopkick/Retail"
                abs(freq - 20200) <= FREQUENCY_TOLERANCE -> "Samba TV/ACR"
                abs(freq - 20500) <= FREQUENCY_TOLERANCE -> "Location Beacon"
                abs(freq - 21000) <= FREQUENCY_TOLERANCE -> "Retail Beacon"
                abs(freq - 21500) <= FREQUENCY_TOLERANCE -> "Inscape/Smart TV"
                abs(freq - 22000) <= FREQUENCY_TOLERANCE -> "Data Plus Math/Attribution"
                else -> "Unknown Ultrasonic Source"
            }

            // Update or create beacon detection
            val existing = activeBeacons[freq]
            val beacon: BeaconDetection
            val shouldReportAnomaly: Boolean

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
                // Track frequency history for stability analysis (true beacons are stable +/-10Hz)
                existing.frequencyHistory.add(freq)
                if (existing.frequencyHistory.size > 30) {
                    existing.frequencyHistory.removeAt(0)
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
                beacon = existing
                // Report anomaly when detection count reaches threshold (only once)
                shouldReportAnomaly = existing.detectionCount == MIN_DETECTIONS_TO_CONFIRM
            } else {
                // New beacon detected - create but don't report anomaly yet (wait for persistence)
                val initialAmplitudeHistory = mutableListOf(avgAmplitude)
                val initialLocationHistory = mutableListOf<LocationEntry>()
                val initialFrequencyHistory = mutableListOf(freq)
                currentLatitude?.let { lat ->
                    currentLongitude?.let { lon ->
                        initialLocationHistory.add(LocationEntry(now, lat, lon))
                    }
                }

                beacon = BeaconDetection(
                    frequency = freq,
                    firstDetected = now,
                    lastDetected = now,
                    peakAmplitudeDb = peakAmplitude,
                    possibleSource = possibleSource,
                    latitude = currentLatitude,
                    longitude = currentLongitude,
                    amplitudeHistory = initialAmplitudeHistory,
                    locationHistory = initialLocationHistory,
                    frequencyHistory = initialFrequencyHistory
                )
                activeBeacons[freq] = beacon

                addTimelineEvent(
                    type = UltrasonicEventType.BEACON_DETECTED,
                    title = "📢 Potential Beacon: ${freq}Hz",
                    description = "${possibleSource} - waiting for confirmation",
                    frequency = freq,
                    threatLevel = ThreatLevel.INFO  // Low severity until confirmed
                )

                // Don't report anomaly yet - wait for multiple detections
                shouldReportAnomaly = false
            }

            // Report anomaly only after the beacon has been detected multiple times
            if (shouldReportAnomaly) {
                val analysis = buildBeaconAnalysis(beacon)
                val detectionDuration = beacon.lastDetected - beacon.firstDetected

                // ===== ENTERPRISE-GRADE ALERT GATING =====
                // Gate 1: Duration-based filtering (true beacons persist > 5 seconds)
                val hasSufficientDuration = detectionDuration >= MIN_PERSISTENCE_FOR_ALERT_MS

                // Gate 2: Tracking likelihood threshold gating
                // Below 40% = INFO only, don't create anomaly record
                val meetsTrackingThreshold = analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_LOW

                // Gate 3: False positive suppression
                val isLikelyFalsePositive = analysis.falsePositiveLikelihood > 60f

                // Gate 4: Frequency stability check (true beacons are stable)
                val hasStableFrequency = analysis.isFrequencyStable

                // Combined gating decision
                val shouldCreateAnomaly = hasSufficientDuration &&
                    meetsTrackingThreshold &&
                    !isLikelyFalsePositive &&
                    (hasStableFrequency || analysis.matchedSource != KnownBeaconType.UNKNOWN)

                // Log gating decisions for debugging
                Log.d(TAG, "Beacon ${freq}Hz gating: duration=${detectionDuration}ms (need $MIN_PERSISTENCE_FOR_ALERT_MS), " +
                    "likelihood=${analysis.trackingLikelihood}% (need $TRACKING_LIKELIHOOD_LOW%), " +
                    "fpLikelihood=${analysis.falsePositiveLikelihood}%, " +
                    "freqStable=${analysis.isFrequencyStable}, " +
                    "decision=$shouldCreateAnomaly")

                if (!hasSufficientDuration) {
                    // Too short duration - likely transient noise
                    Log.d(TAG, "Suppressing beacon ${freq}Hz - insufficient duration (${detectionDuration}ms < ${MIN_PERSISTENCE_FOR_ALERT_MS}ms)")
                    addTimelineEvent(
                        type = UltrasonicEventType.BEACON_DETECTED,
                        title = "Transient signal: ${freq}Hz",
                        description = "Brief detection (${detectionDuration/1000}s) - monitoring for persistence",
                        frequency = freq,
                        threatLevel = ThreatLevel.INFO
                    )
                } else if (!meetsTrackingThreshold) {
                    // Below 40% tracking likelihood - INFO only, no anomaly record
                    Log.d(TAG, "Suppressing beacon ${freq}Hz - tracking likelihood too low (${analysis.trackingLikelihood}% < $TRACKING_LIKELIHOOD_LOW%)")
                    addTimelineEvent(
                        type = UltrasonicEventType.BEACON_DETECTED,
                        title = "Low-confidence signal: ${freq}Hz",
                        description = "Tracking likelihood ${String.format("%.0f", analysis.trackingLikelihood)}% - likely environmental noise",
                        frequency = freq,
                        threatLevel = ThreatLevel.INFO
                    )
                } else if (isLikelyFalsePositive) {
                    // High false positive likelihood
                    Log.d(TAG, "Suppressing beacon ${freq}Hz - FP likelihood ${analysis.falsePositiveLikelihood}%")
                    addTimelineEvent(
                        type = UltrasonicEventType.BEACON_DETECTED,
                        title = "Possible noise: ${freq}Hz",
                        description = buildFalsePositiveDescription(analysis),
                        frequency = freq,
                        threatLevel = ThreatLevel.INFO
                    )
                } else if (shouldCreateAnomaly) {
                    // ===== CREATE ANOMALY RECORD =====
                    val anomalyType = determineAnomalyType(possibleSource, isKnownBeacon, analysis)

                    // Determine confidence based on tracking likelihood thresholds
                    val baseConfidence = when {
                        analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_HIGH -> AnomalyConfidence.CRITICAL
                        analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_MEDIUM -> AnomalyConfidence.HIGH
                        analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_LOW -> AnomalyConfidence.MEDIUM
                        else -> AnomalyConfidence.LOW  // Should not reach here due to gating
                    }

                    // Adjust confidence based on FP likelihood
                    val confidence = if (analysis.falsePositiveLikelihood > 40f) {
                        when (baseConfidence) {
                            AnomalyConfidence.CRITICAL -> AnomalyConfidence.HIGH
                            AnomalyConfidence.HIGH -> AnomalyConfidence.MEDIUM
                            else -> AnomalyConfidence.LOW
                        }
                    } else baseConfidence

                    // Determine threat level for timeline
                    val timelineThreatLevel = when {
                        analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_HIGH -> ThreatLevel.HIGH
                        analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_MEDIUM -> ThreatLevel.MEDIUM
                        else -> ThreatLevel.LOW
                    }

                    addTimelineEvent(
                        type = UltrasonicEventType.BEACON_DETECTED,
                        title = "Confirmed: ${analysis.probableSourceDescription}",
                        description = "${analysis.whatItDoes}. ${analysis.recommendedAction}",
                        frequency = freq,
                        threatLevel = timelineThreatLevel
                    )

                    reportAnomaly(
                        type = anomalyType,
                        description = buildEnrichedDescription(analysis, freq),
                        technicalDetails = buildBeaconTechnicalDetails(analysis),
                        frequency = freq,
                        amplitudeDb = peakAmplitude,
                        confidence = confidence,
                        contributingFactors = buildBeaconContributingFactors(analysis)
                    )
                }
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

        // Only show confirmed beacons in UI (detected multiple times)
        _activeBeacons.value = activeBeacons.values
            .filter { it.detectionCount >= MIN_DETECTIONS_TO_CONFIRM }
            .toList()
    }

    private fun updateStatus(detectedFrequencies: Map<Int, MutableList<Double>>) {
        val peakEntry = detectedFrequencies.maxByOrNull {
            it.value.maxOrNull() ?: Double.MIN_VALUE
        }

        // Only count confirmed beacons for threat level
        val confirmedBeacons = activeBeacons.filter { it.value.detectionCount >= MIN_DETECTIONS_TO_CONFIRM }
        val threatLevel = when {
            confirmedBeacons.any { beacon ->
                TrackerDatabase.findUltrasonicByFrequency(beacon.key, FREQUENCY_TOLERANCE) != null
            } -> ThreatLevel.HIGH
            confirmedBeacons.isNotEmpty() -> ThreatLevel.MEDIUM
            detectedFrequencies.isNotEmpty() -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }

        _status.value = UltrasonicStatus(
            isScanning = isMonitoring,
            lastScanTime = System.currentTimeMillis(),
            noiseFloorDb = noiseFloorDb,
            ultrasonicActivityDetected = detectedFrequencies.isNotEmpty(),
            activeBeaconCount = confirmedBeacons.size,  // Only count confirmed beacons
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
            detectionCount = MIN_DETECTIONS_TO_CONFIRM,  // Ensure test beacon shows in UI
            possibleSource = "TEST: SilverPush/Ad Tracking",
            latitude = currentLatitude,
            longitude = currentLongitude
        )
        activeBeacons[testFrequency] = beacon
        _activeBeacons.value = activeBeacons.values
            .filter { it.detectionCount >= MIN_DETECTIONS_TO_CONFIRM }
            .toList()

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

        val confirmedCount = activeBeacons.count { it.value.detectionCount >= MIN_DETECTIONS_TO_CONFIRM }
        _status.value = UltrasonicStatus(
            isScanning = isMonitoring,
            lastScanTime = System.currentTimeMillis(),
            noiseFloorDb = noiseFloorDb,
            ultrasonicActivityDetected = true,
            activeBeaconCount = confirmedCount,
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
     * Determine source category based on beacon type and frequency
     */
    private fun getSourceCategory(source: KnownBeaconType, freq: Int): SourceCategory {
        return when (source) {
            KnownBeaconType.SILVERPUSH -> SourceCategory.ADVERTISING
            KnownBeaconType.ALPHONSO -> SourceCategory.ADVERTISING
            KnownBeaconType.SIGNAL360 -> SourceCategory.ANALYTICS
            KnownBeaconType.LISNR -> SourceCategory.TRACKING
            KnownBeaconType.SHOPKICK -> SourceCategory.RETAIL
            KnownBeaconType.SAMBA_TV -> SourceCategory.ADVERTISING  // Smart TV ACR
            KnownBeaconType.ZAPR -> SourceCategory.ADVERTISING      // TV attribution
            KnownBeaconType.TVISION -> SourceCategory.ANALYTICS     // TV viewership
            KnownBeaconType.UNKNOWN -> {
                when {
                    freq in 17000..18500 -> SourceCategory.ADVERTISING  // Ad tracking band
                    freq in 19500..20500 -> SourceCategory.RETAIL       // Retail beacon band
                    freq >= 20500 -> SourceCategory.TRACKING            // Higher freq = more suspicious
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
     * Group location entries by geographic proximity.
     *
     * Used to analyze dwell time at each distinct location. A real tracker following you
     * will show persistent detection at each location (you stop somewhere, it stays with you).
     * Different TVs will show brief detections as you walk past each house.
     *
     * @param locationHistory List of location entries with timestamps
     * @param proximityMeters Distance threshold for grouping (default 100m)
     * @return List of location groups, each containing entries within proximityMeters of each other
     */
    private fun groupLocationsByProximity(
        locationHistory: List<LocationEntry>,
        proximityMeters: Double
    ): List<List<LocationEntry>> {
        if (locationHistory.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<LocationEntry>>()
        val assigned = mutableSetOf<Int>()

        for ((idx, entry) in locationHistory.withIndex()) {
            if (idx in assigned) continue

            // Start a new group with this entry
            val group = mutableListOf(entry)
            assigned.add(idx)

            // Find all other entries within proximity
            for ((otherIdx, other) in locationHistory.withIndex()) {
                if (otherIdx in assigned) continue
                if (otherIdx == idx) continue

                val distance = haversineDistanceMeters(
                    entry.latitude, entry.longitude,
                    other.latitude, other.longitude
                )
                if (distance <= proximityMeters) {
                    group.add(other)
                    assigned.add(otherIdx)
                }
            }

            groups.add(group)
        }

        return groups
    }

    /**
     * Build comprehensive beacon analysis with enterprise-grade heuristics
     */
    private fun buildBeaconAnalysis(beacon: BeaconDetection): BeaconAnalysis {
        val amplitudes = beacon.amplitudeHistory
        val locations = beacon.locationHistory
        val frequencies = beacon.frequencyHistory

        // Amplitude analysis
        val avgAmplitude = if (amplitudes.isNotEmpty()) amplitudes.average() else beacon.peakAmplitudeDb
        val amplitudeVariance = if (amplitudes.size > 1) {
            amplitudes.map { (it - avgAmplitude) * (it - avgAmplitude) }.average().toFloat()
        } else 0f

        val profile = analyzeAmplitudeProfile(amplitudes)

        // ===== FREQUENCY STABILITY ANALYSIS =====
        // True beacons are precise (+/-10Hz), environmental noise drifts
        val frequencyStability = if (frequencies.size > 2) {
            val avgFreq = frequencies.average()
            sqrt(frequencies.map { (it - avgFreq) * (it - avgFreq) }.average()).toFloat()
        } else 0f
        val isFrequencyStable = frequencyStability <= FREQUENCY_STABILITY_TOLERANCE_HZ

        // Source attribution with enhanced fingerprinting
        val (matchedSource, confidence) = attributeBeaconSourceEnhanced(beacon.frequency, avgAmplitude, profile, isFrequencyStable)
        val category = getSourceCategory(matchedSource, beacon.frequency)

        // ===== MODULATION PATTERN DETECTION =====
        val (hasKnownModulation, modulationType) = detectModulationPattern(amplitudes, matchedSource)

        // Cross-location analysis
        val distinctLocations = countDistinctLocations(locations)
        val detectionDuration = beacon.lastDetected - beacon.firstDetected

        // ===== CROSS-LOCATION FOLLOWING DETECTION =====
        // CRITICAL FIX: Distinguish between:
        // 1. SAME beacon following user (e.g., tracker in bag) - TRUE THREAT
        // 2. SIMILAR frequency from DIFFERENT sources (e.g., different TVs in neighborhood) - FALSE POSITIVE
        //
        // Walking through a suburb, different homes' TVs may emit 18kHz SilverPush/Alphonso beacons.
        // These are DIFFERENT beacons (different TVs), not one tracker following you.
        //
        // Indicators that it's truly the SAME beacon following you:
        // - Consistent amplitude fingerprint across locations (same device = similar signal characteristics)
        // - Stable frequency (same oscillator = same frequency drift pattern)
        // - Temporal correlation (appears shortly after you arrive at each location)
        // - NOT a known TV/ad tracking frequency (those are expected from multiple TVs)
        //
        // Indicators that it's DIFFERENT sources (neighborhood TVs):
        // - Known TV ad beacon frequency (17.5-18.5kHz = SilverPush/Alphonso/Zapr TV ad range)
        // - High amplitude variance across locations (different TVs = different signal strengths)
        // - Detected only while passing by (not persistent at each location)
        // - Outdoor/walking context with residential surroundings

        val isKnownTvAdFrequency = beacon.frequency in 17400..18600  // TV ad beacon band
        val isKnownRetailFrequency = beacon.frequency in 19900..20300  // Shopkick/retail band
        val isKnownSmartTvFrequency = beacon.frequency in 20100..21600  // Samba TV/Inscape smart TV ACR

        // Calculate amplitude consistency across locations
        // A REAL tracker following you would have consistent amplitude (same device)
        // Different TVs in different houses would have HIGH variance (different distances, walls, etc.)
        val amplitudeConsistency = if (amplitudes.size >= 3) {
            val avgAmp = amplitudes.average()
            val variance = amplitudes.map { (it - avgAmp) * (it - avgAmp) }.average()
            val stdDev = sqrt(variance)
            // Coefficient of variation: stdDev / |mean| - lower = more consistent
            // Trackers typically have CV < 0.15 (15% variation)
            // Different TVs typically have CV > 0.3 (30%+ variation)
            if (avgAmp != 0.0) (stdDev / abs(avgAmp)).toFloat() else 1.0f
        } else 1.0f  // Insufficient data = assume inconsistent

        // Check for temporal clustering at each location
        // Real tracker: persistent signal at each location (stays with you)
        // Different TVs: brief detections as you walk past
        val avgDwellTimePerLocation = if (distinctLocations > 0 && locations.isNotEmpty()) {
            // Group locations by proximity and calculate average time spent detecting at each
            val locationGroups = groupLocationsByProximity(locations, 100.0) // 100m clusters
            val dwellTimes = locationGroups.map { group ->
                if (group.size >= 2) {
                    group.maxOf { it.timestamp } - group.minOf { it.timestamp }
                } else 0L
            }
            if (dwellTimes.isNotEmpty()) dwellTimes.average().toLong() else 0L
        } else 0L

        // FOLLOWING USER DETERMINATION
        // Require STRONG evidence for cross-location tracking alert:
        // 1. Must have 3+ distinct locations (2 could be coincidence - neighbor's TV from two spots)
        // 2. For TV ad frequencies (17.4-18.6kHz), require amplitude consistency (same device)
        // 3. For unknown frequencies, require persistence at multiple locations
        val followingUser = when {
            // Not enough distinct locations - don't flag as following
            distinctLocations < 3 -> false

            // Known TV ad frequency (SilverPush, Alphonso, Zapr)
            // These are EXPECTED from different TVs in a neighborhood
            // Only flag if amplitude is HIGHLY consistent (suggests same device)
            isKnownTvAdFrequency -> {
                amplitudeConsistency < 0.15f && // Very consistent amplitude = same device
                isFrequencyStable &&            // Stable frequency = same oscillator
                avgDwellTimePerLocation > 30_000L  // 30+ seconds per location = persistent
            }

            // Known retail beacon frequency (Shopkick)
            // Expected in retail areas but not in suburbs
            isKnownRetailFrequency -> {
                // In a residential suburb, retail beacons at 3+ locations is suspicious
                amplitudeConsistency < 0.25f
            }

            // Known smart TV ACR frequency (Samba TV, Inscape)
            // Very common from smart TVs - only flag with strong evidence
            isKnownSmartTvFrequency -> {
                amplitudeConsistency < 0.12f && // Extremely consistent
                avgDwellTimePerLocation > 60_000L  // 1+ minute per location
            }

            // Unknown frequency - more suspicious, lower threshold
            else -> {
                amplitudeConsistency < 0.3f || // Either consistent amplitude
                avgDwellTimePerLocation > 20_000L  // Or persistent presence
            }
        }

        // Environmental context
        val envContext = beacon.environmentalContext

        // Persistence score (how long has this been active)
        val persistenceScore = when {
            detectionDuration > 300_000 -> 1.0f  // > 5 minutes
            detectionDuration > 120_000 -> 0.7f  // > 2 minutes
            detectionDuration > 60_000 -> 0.5f   // > 1 minute
            detectionDuration > 30_000 -> 0.3f   // > 30 seconds
            else -> 0.1f
        }

        // Signal-to-noise ratio
        val snrDb = avgAmplitude - noiseFloorDb

        // ===== RISK INDICATORS =====
        val riskIndicators = mutableListOf<String>()
        if (matchedSource != KnownBeaconType.UNKNOWN) {
            riskIndicators.add("Matches ${matchedSource.company} beacon signature")
        }
        if (followingUser) {
            riskIndicators.add("CRITICAL: Detected at $distinctLocations different locations - beacon may be following you")
        }
        if (beacon.seenAtOtherLocations > 0 && beacon.seenAtHomeLocation) {
            riskIndicators.add("CRITICAL: Same beacon detected at your home AND ${ beacon.seenAtOtherLocations} other locations")
        }
        if (persistenceScore > 0.5f) {
            riskIndicators.add("Persistent signal (${detectionDuration / 1000}s)")
        }
        if (snrDb > 20) {
            riskIndicators.add("Strong signal (SNR: ${String.format("%.1f", snrDb)} dB)")
        }
        if (profile == AmplitudeProfile.PULSING || profile == AmplitudeProfile.MODULATED) {
            riskIndicators.add("${profile.displayName} pattern - typical of beacon encoding")
        }
        if (beacon.detectionCount > 10) {
            riskIndicators.add("Repeatedly detected (${beacon.detectionCount} times)")
        }
        if (isFrequencyStable) {
            riskIndicators.add("Precise frequency (stable within +/-${FREQUENCY_STABILITY_TOLERANCE_HZ}Hz) - beacon characteristic")
        }
        if (hasKnownModulation) {
            riskIndicators.add("Known modulation pattern detected: $modulationType")
        }

        // ===== TRACKING LIKELIHOOD CALCULATION =====
        var trackingLikelihood = confidence * 0.4f

        // Boost for cross-location detection (strongest indicator)
        if (followingUser) trackingLikelihood += 25f
        if (beacon.seenAtOtherLocations > 0 && beacon.seenAtHomeLocation) trackingLikelihood += 30f

        // Boost for persistence
        if (persistenceScore > 0.5f) trackingLikelihood += 15f
        if (detectionDuration > MIN_PERSISTENCE_FOR_ALERT_MS) trackingLikelihood += 10f

        // Boost for beacon-like characteristics
        if (profile == AmplitudeProfile.PULSING) trackingLikelihood += 10f
        if (profile == AmplitudeProfile.MODULATED) trackingLikelihood += 8f
        if (snrDb > 20) trackingLikelihood += 10f
        if (isFrequencyStable) trackingLikelihood += 12f
        if (hasKnownModulation) trackingLikelihood += 15f

        // Category-based adjustments
        if (category == SourceCategory.TRACKING) trackingLikelihood += 10f
        if (category == SourceCategory.ADVERTISING) trackingLikelihood += 5f

        // Environmental context adjustments
        trackingLikelihood *= envContext.baseThreatMultiplier
        if (envContext == EnvironmentalContext.HOME && !followingUser) {
            // Home location without cross-location = likely your own device
            trackingLikelihood *= 0.5f
        }

        // ===== FALSE POSITIVE HEURISTICS =====
        val fpIndicators = mutableListOf<String>()
        var fpScore = 0f

        // Count concurrent beacons (detected within 5 seconds of this one)
        val concurrentWindow = 5000L
        val concurrentBeacons = activeBeacons.count { (_, otherBeacon) ->
            otherBeacon.frequency != beacon.frequency &&
            abs(otherBeacon.firstDetected - beacon.firstDetected) < concurrentWindow
        }

        // FP Indicator 1: Many beacons detected simultaneously (ambient noise pattern)
        if (concurrentBeacons > MAX_CONCURRENT_BEACONS_FOR_NOISE) {
            fpScore += 35f
            fpIndicators.add("Detected with $concurrentBeacons other frequencies simultaneously (noise burst pattern)")
        } else if (concurrentBeacons >= 3) {
            fpScore += 15f
            fpIndicators.add("Detected with $concurrentBeacons other frequencies (possible ambient noise)")
        }

        // FP Indicator 2: Low persistence / few detections
        if (beacon.detectionCount <= MIN_DETECTIONS_TO_CONFIRM) {
            fpScore += 20f
            fpIndicators.add("Low detection count (${beacon.detectionCount}) - may be transient noise")
        }

        // FP Indicator 3: Very short detection duration
        if (detectionDuration < MIN_PERSISTENCE_FOR_ALERT_MS && beacon.detectionCount <= 3) {
            fpScore += 20f
            fpIndicators.add("Very brief detection (<${MIN_PERSISTENCE_FOR_ALERT_MS/1000}s) - typical of noise spikes")
        }

        // FP Indicator 4: High amplitude variance (unstable = noise, true beacons are consistent)
        if (amplitudeVariance > 50f) {
            fpScore += 25f
            fpIndicators.add("High amplitude variance (${String.format("%.1f", amplitudeVariance)}) - unstable signal")
        } else if (amplitudeVariance > AMPLITUDE_STABILITY_THRESHOLD * AMPLITUDE_STABILITY_THRESHOLD) {
            fpScore += 10f
            fpIndicators.add("Moderate amplitude variance - somewhat unstable")
        }

        // FP Indicator 5: Unknown source with low SNR
        if (matchedSource == KnownBeaconType.UNKNOWN && snrDb < 25) {
            fpScore += 15f
            fpIndicators.add("Unknown source with weak signal - likely background noise")
        }

        // FP Indicator 6: Frequency instability (drifting = environmental noise)
        if (!isFrequencyStable && frequencies.size > 2) {
            fpScore += 20f
            fpIndicators.add("Frequency drift (${String.format("%.1f", frequencyStability)}Hz variance) - true beacons are precise")
        }

        // FP Indicator 7: No cross-location detection after extended time
        if (!followingUser && detectionDuration > 120_000) {
            fpScore += 10f
            fpIndicators.add("Single location only after 2+ minutes - may be local interference")
        }

        // FP Indicator 8: ERRATIC amplitude profile (random = noise)
        if (profile == AmplitudeProfile.ERRATIC) {
            fpScore += 25f
            fpIndicators.add("Erratic amplitude profile - characteristic of environmental noise")
        }

        // FP Indicator 9: FLAT amplitude profile with unknown source (electronic interference)
        if (profile == AmplitudeProfile.STEADY && matchedSource == KnownBeaconType.UNKNOWN) {
            fpScore += 15f
            fpIndicators.add("Flat amplitude profile with unknown source - typical of electronic interference")
        }

        // FP Indicator 10: Frequency matches common false positive sources
        val fpSourceMatch = identifyFalsePositiveSource(beacon.frequency)
        if (fpSourceMatch != null && matchedSource == KnownBeaconType.UNKNOWN) {
            fpScore += 15f
            fpIndicators.add("Frequency matches ${fpSourceMatch.displayName} range")
        }

        // FP Indicator 11: TV ad beacon frequency at multiple locations with inconsistent amplitude
        // Walking through a neighborhood with TVs on in different houses will trigger 18kHz detections
        // at multiple locations - but these are DIFFERENT sources, not a tracker following you.
        // Key distinction: same tracker = consistent amplitude, different TVs = varying amplitude
        if (isKnownTvAdFrequency && distinctLocations >= 2 && !followingUser) {
            fpScore += 30f
            fpIndicators.add("TV ad beacon frequency (${beacon.frequency}Hz) detected at $distinctLocations locations with inconsistent signal - likely different TVs, not a tracker")
        }

        // FP Indicator 12: Smart TV ACR frequency without strong tracking indicators
        // Samba TV / Inscape beacons are extremely common from smart TVs
        if (isKnownSmartTvFrequency && !followingUser && amplitudeConsistency > 0.2f) {
            fpScore += 25f
            fpIndicators.add("Smart TV ACR frequency (${beacon.frequency}Hz) - common from household TVs")
        }

        // FP Indicator 13: EV/Vehicle pedestrian warning system (AVAS)
        // Electric vehicles emit 17-20kHz tones when reversing or at low speeds
        // Characteristics: brief (<15s), outdoor, rising-then-falling amplitude as vehicle passes
        val isEvWarningFrequency = beacon.frequency in 17000..20000
        val isBriefDetection = detectionDuration < 15_000 && beacon.detectionCount <= 5
        val isOutdoorLikely = envContext == EnvironmentalContext.OUTDOOR_RANDOM || envContext == EnvironmentalContext.UNKNOWN
        if (isEvWarningFrequency && isBriefDetection && isOutdoorLikely && !followingUser) {
            fpScore += 35f
            fpIndicators.add("Matches EV pedestrian warning pattern (${beacon.frequency}Hz, brief ${detectionDuration/1000}s detection) - common from reversing electric vehicles")
        }

        // Reduce FP score if we have strong tracking indicators
        if (followingUser) fpScore -= 30f
        if (beacon.seenAtOtherLocations > 0 && beacon.seenAtHomeLocation) fpScore -= 40f
        if (persistenceScore > 0.7f) fpScore -= 20f
        if (matchedSource != KnownBeaconType.UNKNOWN && confidence > 70f) fpScore -= 35f
        if (profile == AmplitudeProfile.PULSING || profile == AmplitudeProfile.MODULATED) fpScore -= 15f
        if (isFrequencyStable) fpScore -= 20f
        if (hasKnownModulation) fpScore -= 25f

        val isLikelyAmbientNoise = concurrentBeacons > MAX_CONCURRENT_BEACONS_FOR_NOISE ||
            (concurrentBeacons >= 3 && matchedSource == KnownBeaconType.UNKNOWN)
        val isLikelyDeviceArtifact = (profile == AmplitudeProfile.STEADY || profile == AmplitudeProfile.ERRATIC) &&
            matchedSource == KnownBeaconType.UNKNOWN &&
            !followingUser &&
            !isFrequencyStable

        // ===== GENERATE ENRICHED DESCRIPTIONS =====
        val probableSource = generateProbableSourceDescription(matchedSource, beacon.frequency, category)
        val whatItDoes = generateWhatItDoesDescription(matchedSource, category, followingUser)
        val recommendedAction = generateRecommendedAction(
            trackingLikelihood.coerceIn(0f, 100f),
            followingUser,
            matchedSource,
            envContext
        )

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
            environmentalContext = envContext,
            trackingLikelihood = trackingLikelihood.coerceIn(0f, 100f),
            riskIndicators = riskIndicators,
            falsePositiveLikelihood = fpScore.coerceIn(0f, 100f),
            fpIndicators = fpIndicators,
            concurrentBeaconCount = concurrentBeacons,
            isLikelyAmbientNoise = isLikelyAmbientNoise,
            isLikelyDeviceArtifact = isLikelyDeviceArtifact,
            frequencyStabilityHz = frequencyStability,
            isFrequencyStable = isFrequencyStable,
            hasKnownModulationPattern = hasKnownModulation,
            modulationPatternType = modulationType,
            probableSourceDescription = probableSource,
            whatItDoes = whatItDoes,
            recommendedAction = recommendedAction,
            isFollowingAcrossLocations = followingUser || (beacon.seenAtOtherLocations > 0 && beacon.seenAtHomeLocation)
        )
    }

    /**
     * Enhanced source attribution with modulation pattern consideration
     */
    private fun attributeBeaconSourceEnhanced(
        freq: Int,
        amplitude: Double,
        profile: AmplitudeProfile,
        isFrequencyStable: Boolean
    ): Pair<KnownBeaconType, Float> {
        // Look up in TrackerDatabase first
        val dbSignature = TrackerDatabase.findUltrasonicByFrequency(freq, FREQUENCY_TOLERANCE)
        if (dbSignature != null) {
            // Found in database - use database info
            val baseConfidence = when (dbSignature.trackingPurpose) {
                UltrasonicTrackingPurpose.AD_TRACKING -> 90f
                UltrasonicTrackingPurpose.TV_ATTRIBUTION -> 85f
                UltrasonicTrackingPurpose.CROSS_DEVICE_LINKING -> 90f
                UltrasonicTrackingPurpose.RETAIL_ANALYTICS -> 75f
                UltrasonicTrackingPurpose.LOCATION_VERIFICATION -> 70f
                UltrasonicTrackingPurpose.PRESENCE_DETECTION -> 65f
                else -> 60f
            }

            // Adjust confidence based on signal characteristics
            var confidence = baseConfidence
            if (isFrequencyStable) confidence += 10f
            if (profile == AmplitudeProfile.PULSING || profile == AmplitudeProfile.MODULATED) confidence += 10f

            val beaconType = when {
                dbSignature.manufacturer.contains("SilverPush", ignoreCase = true) -> KnownBeaconType.SILVERPUSH
                dbSignature.manufacturer.contains("Alphonso", ignoreCase = true) -> KnownBeaconType.ALPHONSO
                dbSignature.manufacturer.contains("Signal360", ignoreCase = true) -> KnownBeaconType.SIGNAL360
                dbSignature.manufacturer.contains("LISNR", ignoreCase = true) -> KnownBeaconType.LISNR
                dbSignature.manufacturer.contains("Shopkick", ignoreCase = true) -> KnownBeaconType.SHOPKICK
                else -> KnownBeaconType.UNKNOWN
            }

            return beaconType to confidence.coerceIn(0f, 100f)
        }

        // Fallback to frequency-based matching with enhanced beacon database
        return when {
            // Zapr: ~17.5kHz, TV attribution (India)
            abs(freq - 17500) <= FREQUENCY_TOLERANCE -> {
                var confidence = 75f
                if (isFrequencyStable) confidence += 10f
                KnownBeaconType.ZAPR to confidence
            }

            // SilverPush: ~18kHz, FSK modulation, 2-5 second beacons
            abs(freq - 18000) <= FREQUENCY_TOLERANCE -> {
                var confidence = 80f
                if (profile == AmplitudeProfile.PULSING) confidence += 10f  // FSK modulation shows as pulsing
                if (isFrequencyStable) confidence += 5f
                KnownBeaconType.SILVERPUSH to confidence
            }

            // Alphonso: ~18.5kHz, PSK modulation, always-on ACR
            abs(freq - 18500) <= FREQUENCY_TOLERANCE -> {
                var confidence = 80f
                if (profile == AmplitudeProfile.MODULATED) confidence += 10f  // PSK modulation
                if (isFrequencyStable) confidence += 5f
                KnownBeaconType.ALPHONSO to confidence
            }

            // Signal360: ~19kHz, malls and airports
            abs(freq - 19000) <= FREQUENCY_TOLERANCE -> {
                KnownBeaconType.SIGNAL360 to if (isFrequencyStable) 75f else 65f
            }

            // LISNR: ~19.5kHz, CHIRP modulation, higher bandwidth
            abs(freq - 19500) <= FREQUENCY_TOLERANCE -> {
                var confidence = 75f
                if (profile == AmplitudeProfile.MODULATED) confidence += 15f  // CHIRP shows as modulated
                if (isFrequencyStable) confidence += 5f
                KnownBeaconType.LISNR to confidence
            }

            // TVision: ~19.8kHz, TV viewership measurement
            abs(freq - 19800) <= FREQUENCY_TOLERANCE -> {
                var confidence = 70f
                if (isFrequencyStable) confidence += 10f
                KnownBeaconType.TVISION to confidence
            }

            // Shopkick: ~20kHz, retail presence (Target, Macy's, Best Buy, etc.)
            abs(freq - 20000) <= FREQUENCY_TOLERANCE || abs(freq - 20100) <= FREQUENCY_TOLERANCE -> {
                KnownBeaconType.SHOPKICK to if (isFrequencyStable) 70f else 60f
            }

            // Samba TV / Inscape: ~20.2-21.5kHz, smart TV ACR
            abs(freq - 20200) <= FREQUENCY_TOLERANCE ||
            abs(freq - 20800) <= FREQUENCY_TOLERANCE ||
            abs(freq - 21500) <= FREQUENCY_TOLERANCE -> {
                var confidence = 75f
                if (isFrequencyStable) confidence += 10f
                // Smart TV ACR is typically very stable
                if (profile == AmplitudeProfile.STEADY) confidence += 5f
                KnownBeaconType.SAMBA_TV to confidence
            }

            else -> {
                // Unknown source - lower confidence unless strong beacon characteristics
                var confidence = 25f
                if (isFrequencyStable) confidence += 15f
                if (profile == AmplitudeProfile.PULSING || profile == AmplitudeProfile.MODULATED) confidence += 10f
                KnownBeaconType.UNKNOWN to confidence
            }
        }
    }

    /**
     * Detect known modulation patterns from amplitude history
     */
    private fun detectModulationPattern(amplitudes: List<Double>, source: KnownBeaconType): Pair<Boolean, String?> {
        if (amplitudes.size < 10) return false to null

        val profile = analyzeAmplitudeProfile(amplitudes)

        return when (source) {
            KnownBeaconType.SILVERPUSH -> {
                // SilverPush uses FSK modulation - shows as regular on/off pulsing
                if (profile == AmplitudeProfile.PULSING) {
                    true to "FSK (Frequency Shift Keying)"
                } else false to null
            }
            KnownBeaconType.ALPHONSO -> {
                // Alphonso uses PSK modulation - shows as phase-shifted patterns
                if (profile == AmplitudeProfile.MODULATED || profile == AmplitudeProfile.PULSING) {
                    true to "PSK (Phase Shift Keying)"
                } else false to null
            }
            KnownBeaconType.LISNR -> {
                // LISNR uses CHIRP modulation - frequency sweeps
                if (profile == AmplitudeProfile.MODULATED) {
                    true to "CHIRP (Frequency Sweep)"
                } else false to null
            }
            else -> {
                // Check for generic beacon patterns
                if (profile == AmplitudeProfile.PULSING) {
                    true to "Unknown Pulsed Modulation"
                } else if (profile == AmplitudeProfile.MODULATED) {
                    true to "Unknown Data Modulation"
                } else false to null
            }
        }
    }

    /**
     * Identify potential false positive sources based on frequency
     */
    private fun identifyFalsePositiveSource(freq: Int): FalsePositiveSource? {
        return FalsePositiveSource.values().find { source ->
            source.frequencyRanges.any { range -> freq in range }
        }
    }

    /**
     * Get enriched signature details from the TrackerDatabase
     *
     * Returns additional real-world context including:
     * - Confirmation methods for users
     * - Mitigation advice
     * - Privacy impact classification
     * - Legal status
     * - Deployment locations
     */
    private fun getSignatureDetails(freq: Int): SignatureDetails? {
        val signature = TrackerDatabase.findUltrasonicByFrequency(freq, FREQUENCY_TOLERANCE)
            ?: return null

        return SignatureDetails(
            name = signature.name,
            manufacturer = signature.manufacturer,
            description = signature.description,
            trackingPurpose = signature.trackingPurpose,
            confirmationMethod = signature.confirmationMethod,
            mitigationAdvice = signature.mitigationAdvice,
            hasLegitimateUses = signature.hasLegitimateUses,
            deploymentLocations = signature.deploymentLocations
        )
    }

    /**
     * Enriched signature details for display and LLM context
     */
    data class SignatureDetails(
        val name: String,
        val manufacturer: String,
        val description: String,
        val trackingPurpose: UltrasonicTrackingPurpose,
        val confirmationMethod: String?,
        val mitigationAdvice: String?,
        val hasLegitimateUses: Boolean,
        val deploymentLocations: List<String>
    )

    /**
     * Calculate environmental context threat score
     *
     * Environmental Context Scoring:
     * - At home + smart TV on: Likely Samba/Inscape (lower threat)
     * - Retail store: Likely Shopkick (known, lower threat)
     * - Random public place: Unknown source (higher threat)
     * - Multiple locations: Following you (CRITICAL)
     */
    private fun calculateEnvironmentalThreatScore(
        beacon: BeaconDetection,
        envContext: EnvironmentalContext,
        matchedSource: KnownBeaconType
    ): Float {
        var score = 50f  // Base score

        // Environmental context adjustments
        when (envContext) {
            EnvironmentalContext.HOME -> {
                // At home - likely your own devices
                if (matchedSource == KnownBeaconType.UNKNOWN) {
                    // Unknown beacon at home is suspicious
                    score += 10f
                } else {
                    // Known source at home (e.g., smart TV ACR) - lower external threat
                    score -= 15f
                }
            }
            EnvironmentalContext.RETAIL -> {
                // In retail store - beacons expected
                if (matchedSource == KnownBeaconType.SHOPKICK) {
                    // Shopkick in retail = expected
                    score -= 20f
                } else if (matchedSource == KnownBeaconType.UNKNOWN) {
                    // Unknown beacon in retail - moderate
                    score += 5f
                }
            }
            EnvironmentalContext.OUTDOOR_RANDOM -> {
                // Random outdoor location - higher threat
                score += 20f
                if (matchedSource == KnownBeaconType.UNKNOWN) {
                    score += 10f
                }
            }
            EnvironmentalContext.WORK -> {
                // Work environment - moderate
                score += 5f
            }
            EnvironmentalContext.UNKNOWN -> {
                // Default - no adjustment
            }
        }

        // Cross-location detection is the strongest indicator
        if (beacon.seenAtHomeLocation && beacon.seenAtOtherLocations > 0) {
            // Same beacon at home AND elsewhere = CRITICAL
            score += 40f
        }

        val distinctLocations = countDistinctLocations(beacon.locationHistory)
        if (distinctLocations >= 2) {
            // Following user - major escalation
            score += 30f
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Get confirmation instructions based on detected beacon type
     *
     * Real-world confirmation methods:
     * - Check if you have Shopkick, SilverPush SDK apps installed
     * - Note if detection happens near store entrance (Shopkick)
     * - Check if detection correlates with TV commercials
     * - Use another phone with ultrasonic app to cross-verify
     * - Move away from suspected source - signal should drop
     * - Record audio sample for later frequency analysis
     */
    fun getConfirmationInstructions(beacon: BeaconDetection): String {
        val signatureDetails = getSignatureDetails(beacon.frequency)

        // If we have specific confirmation method from database, use it
        if (signatureDetails?.confirmationMethod != null) {
            return signatureDetails.confirmationMethod
        }

        // Otherwise, generate based on source type
        val analysis = buildBeaconAnalysis(beacon)

        return when (analysis.matchedSource) {
            KnownBeaconType.SILVERPUSH, KnownBeaconType.ALPHONSO, KnownBeaconType.ZAPR -> {
                """CONFIRMATION STEPS:
1. Check if detection correlates with TV commercials being on
2. Mute the TV - detection should stop within seconds
3. Use another phone with an ultrasonic detector app to cross-verify
4. Check installed apps for SilverPush/Alphonso/Zapr SDKs (use app scanner)
5. Record audio sample for later frequency analysis

NOTE: These beacons are embedded in TV ads and tracked by apps with mic permission."""
            }
            KnownBeaconType.SHOPKICK -> {
                """CONFIRMATION STEPS:
1. Note if detection happens near store entrance
2. Check if you have Shopkick app installed
3. Move away from the store - signal should drop
4. The beacon is stationary - if it follows you, something else is the source

DEPLOYMENT: Target, Macy's, Best Buy, Walmart, CVS and other major retailers."""
            }
            KnownBeaconType.LISNR -> {
                """CONFIRMATION STEPS:
1. Are you at a ticketing event or using a payment app? May be legitimate.
2. Move away from suspected source - signal should drop if stationary
3. Check if you have apps that use LISNR for payments/check-in
4. If in random location with no clear source - suspicious

NOTE: LISNR has legitimate uses for payments and ticketing."""
            }
            KnownBeaconType.SAMBA_TV, KnownBeaconType.TVISION -> {
                """CONFIRMATION STEPS:
1. Is your smart TV on? This is likely coming from the TV itself.
2. Check smart TV settings for ACR (Automatic Content Recognition):
   - Samsung: Settings > Support > Terms & Policy > Viewing Information Services
   - Vizio: Settings > System > Reset & Admin > Viewing Data
   - LG: Settings > General > LivePlus
   - Sony: Settings > Device Preferences > Samba Interactive TV
3. Turn off the TV - detection should stop

NOTE: This tracks everything you watch including streaming, cable, and HDMI inputs."""
            }
            else -> {
                """CONFIRMATION STEPS:
1. Move away from the location - does the signal drop?
2. Use another phone with ultrasonic app to cross-verify
3. Record audio sample for later frequency analysis
4. Note if signal correlates with specific devices being on
5. Check for ultrasonic pest deterrents, humidifiers, or other devices

FALSE POSITIVE SOURCES:
- Switching power supplies (laptop chargers)
- LCD backlight PWM
- HVAC ultrasonic humidifiers
- Electronic interference"""
            }
        }
    }

    /**
     * Generate human-readable probable source description
     */
    private fun generateProbableSourceDescription(source: KnownBeaconType, freq: Int, category: SourceCategory): String {
        return when (source) {
            KnownBeaconType.SILVERPUSH -> "SilverPush Advertising Beacon (${freq}Hz) - Cross-device ad tracking"
            KnownBeaconType.ALPHONSO -> "Alphonso TV Tracking System (${freq}Hz) - Automated Content Recognition"
            KnownBeaconType.SIGNAL360 -> "Signal360 Marketing Beacon (${freq}Hz) - Location-based advertising"
            KnownBeaconType.LISNR -> "LISNR Cross-Device Tracker (${freq}Hz) - Ultrasonic data transfer"
            KnownBeaconType.SHOPKICK -> "Shopkick Retail Beacon (${freq}Hz) - Store presence detection"
            KnownBeaconType.SAMBA_TV -> "Samba TV / Inscape Smart TV ACR (${freq}Hz) - Viewing data collection"
            KnownBeaconType.ZAPR -> "Zapr TV Attribution (${freq}Hz) - TV content recognition"
            KnownBeaconType.TVISION -> "TVision Viewership (${freq}Hz) - TV viewing measurement"
            KnownBeaconType.UNKNOWN -> {
                when (category) {
                    SourceCategory.RETAIL -> "Unknown Retail Beacon (${freq}Hz)"
                    SourceCategory.ADVERTISING -> "Unknown Ad Beacon (${freq}Hz)"
                    SourceCategory.TRACKING -> "Unknown Tracking Beacon (${freq}Hz)"
                    SourceCategory.ANALYTICS -> "Unknown Analytics Beacon (${freq}Hz)"
                    SourceCategory.UNKNOWN -> "Unknown Ultrasonic Source (${freq}Hz)"
                }
            }
        }
    }

    /**
     * Generate description of what the beacon does
     *
     * Uses real-world knowledge about ultrasonic tracking systems:
     * - Cross-device tracking: Links phone, tablet, laptop, smart TV
     * - De-anonymization: Can link anonymous browsing to real identity
     * - Location history: Tracks store visits, time spent
     * - Ad attribution: Knows which TV ad made you buy
     * - Household mapping: Identifies all devices in home
     */
    private fun generateWhatItDoesDescription(source: KnownBeaconType, category: SourceCategory, followingUser: Boolean): String {
        val baseDescription = when (source) {
            KnownBeaconType.SILVERPUSH ->
                "SilverPush cross-device ad tracking (India). SDK was in 200+ apps (2015-2017). " +
                "Links your phone to TV ads using FSK modulation. Beacons last 2-5 seconds. " +
                "PRIVACY IMPACT: Can de-anonymize your browsing by linking anonymous web activity to your phone's real identity."
            KnownBeaconType.ALPHONSO ->
                "Alphonso Automated Content Recognition (US). Found in 1,000+ apps including games. " +
                "Always-on background listening fingerprints TV audio. FTC investigated in 2018. " +
                "PRIVACY IMPACT: Tracks everything you watch, selling viewing data to advertisers."
            KnownBeaconType.SIGNAL360 ->
                "Signal360 proximity marketing. Deployed in malls and airports. " +
                "Verifies your physical presence for location-based advertising."
            KnownBeaconType.LISNR ->
                "LISNR ultrasonic data transfer (US). Higher bandwidth than others. " +
                "Used for proximity payments, ticketing, and cross-device linking. " +
                "NOTE: Has legitimate uses (check-in, payments) but also enables tracking."
            KnownBeaconType.SHOPKICK ->
                "Shopkick retail presence detection. Deployed in Target, Macy's, Best Buy, Walmart, CVS. " +
                "Usually opt-in for loyalty rewards. Tracks store visits and aisle presence."
            KnownBeaconType.SAMBA_TV ->
                "Samba TV / Inscape Automatic Content Recognition. Built into Samsung, Vizio, LG, Sony smart TVs. " +
                "Tracks EVERYTHING you watch: streaming, cable, gaming, even HDMI inputs. " +
                "PRIVACY IMPACT: Detailed viewing profile sold to advertisers. Vizio paid $2.2M FTC settlement in 2017."
            KnownBeaconType.ZAPR ->
                "Zapr TV content recognition (India). Tracks what TV shows you watch for targeted advertising. " +
                "Links TV viewing to your mobile device for cross-device targeting."
            KnownBeaconType.TVISION ->
                "TVision viewership measurement. Tracks what you watch and for how long. " +
                "Used for TV ratings and advertising analytics."
            KnownBeaconType.UNKNOWN -> when (category) {
                SourceCategory.RETAIL -> "Unknown retail beacon. May track your movement within stores, time spent in aisles."
                SourceCategory.ADVERTISING -> "Unknown advertising beacon. May link your device to ads you've seen."
                SourceCategory.TRACKING -> "Unknown tracking beacon. May track location or link to other devices."
                SourceCategory.ANALYTICS -> "Unknown analytics beacon. May collect data about your device presence."
                SourceCategory.UNKNOWN -> "Unknown ultrasonic source. Purpose unclear - may be tracking your device."
            }
        }

        val followingWarning = if (followingUser) {
            "\n\nCRITICAL: This beacon has been detected at MULTIPLE locations you've visited. " +
            "This is highly unusual - the same beacon following you could indicate: " +
            "(1) A hidden tracking device in your belongings/vehicle, or " +
            "(2) Your phone has a tracking app with this beacon's SDK."
        } else ""

        return baseDescription + followingWarning
    }

    /**
     * Generate recommended action based on analysis
     *
     * Real-world mitigation advice:
     * - Check app permissions for microphone access
     * - Revoke mic permission from apps that don't need it
     * - Use privacy-focused app stores (F-Droid)
     * - Consider ultrasonic blocking apps
     * - Mute TV during commercials to block beacons
     *
     * Legal context:
     * - FTC: Ruled SilverPush-style tracking can be deceptive
     * - GDPR: Requires explicit consent for this tracking
     * - CCPA: Must disclose and allow opt-out
     */
    private fun generateRecommendedAction(
        trackingLikelihood: Float,
        followingUser: Boolean,
        source: KnownBeaconType,
        context: EnvironmentalContext
    ): String {
        return when {
            followingUser -> {
                """URGENT: This beacon appears to be following you across locations.

IMMEDIATE ACTIONS:
1. Check your belongings for hidden tracking devices
2. Inspect your vehicle (wheel wells, under seats, OBD port)
3. Review installed apps - look for apps you don't recognize
4. Check which apps have microphone permission (Settings > Privacy > Microphone)

If you suspect stalking, contact local authorities. Document detection times and locations.

To verify: Use another phone with an ultrasonic detector app to cross-check the signal."""
            }
            trackingLikelihood >= TRACKING_LIKELIHOOD_HIGH -> {
                when (source) {
                    KnownBeaconType.SILVERPUSH, KnownBeaconType.ALPHONSO ->
                        """HIGH TRACKING LIKELIHOOD - Ad/TV Attribution System

ACTIONS TO TAKE:
1. Check app permissions: Settings > Privacy > Microphone
2. Revoke mic access from apps that don't need it (especially games, flashlight apps, etc.)
3. The FTC ruled this type of tracking can be deceptive - you may not have knowingly consented
4. Consider using F-Droid or privacy-focused app stores
5. Mute TV during commercials to prevent beacon reception

CONFIRMATION: Check if this detection correlates with TV commercials being on."""

                    KnownBeaconType.SHOPKICK ->
                        """RETAIL TRACKING BEACON - Shopkick

This beacon is deployed in Target, Macy's, Best Buy, Walmart, CVS and other retailers.

ACTIONS TO TAKE:
1. If you have Shopkick app: This is expected behavior for loyalty rewards
2. If you DON'T have Shopkick: Another app may have embedded their SDK
3. Check which apps have microphone permission
4. The beacon only affects you if an app with the SDK has mic access

CONFIRMATION: Note if detection happens near store entrance."""

                    KnownBeaconType.LISNR ->
                        """CROSS-DEVICE TRACKING BEACON - LISNR

LISNR has legitimate uses (payments, ticketing) but also enables tracking.

ACTIONS TO TAKE:
1. If at a ticketing event or using payment app: May be legitimate
2. Otherwise: Suspicious - check which apps have microphone access
3. Revoke mic permission from untrusted apps
4. Consider ultrasonic blocking apps if this persists

CONFIRMATION: Move away from suspected source - signal should drop if stationary."""

                    else ->
                        """HIGH TRACKING LIKELIHOOD

ACTIONS TO TAKE:
1. Review app permissions: Settings > Privacy > Microphone
2. Revoke mic access from apps that don't genuinely need it
3. Use privacy-focused app stores (F-Droid)
4. Record the audio for later frequency analysis if you want to investigate
5. Move to a different location to see if beacon follows you

Under GDPR/CCPA, this type of tracking requires consent/disclosure."""
                }
            }
            trackingLikelihood >= TRACKING_LIKELIHOOD_MEDIUM -> {
                when (context) {
                    EnvironmentalContext.RETAIL ->
                        """RETAIL BEACON DETECTED

Common in stores for analytics and loyalty programs.

ACTIONS (if you want to avoid):
1. Disable microphone for shopping/retail apps
2. The beacon cannot track you unless an app is actively listening
3. Move away from the suspected source to verify signal drops

No immediate action needed if you're comfortable with retail analytics."""

                    EnvironmentalContext.HOME ->
                        """BEACON DETECTED AT HOME

Likely source: Smart TV, set-top box, or smart speaker.

ACTIONS TO CHECK:
1. Smart TV ACR settings: Look for 'Viewing Data', 'Samba Interactive TV', 'Smart Interactivity'
2. Disable ACR if you don't want your viewing tracked
3. Check if you have Vizio TV - they paid $2.2M FTC settlement for this
4. Consider using external streaming device instead of smart TV apps

If from Samba TV/Inscape: Lower external threat since it's your own device."""

                    else ->
                        """MODERATE TRACKING LIKELIHOOD

MONITORING ADVICE:
1. Continue monitoring - will alert if beacon follows to other locations
2. Note the location and time of detection
3. If detected at multiple locations: This becomes HIGH priority

No immediate action required unless pattern emerges."""
                }
            }
            else -> {
                """LOW TRACKING LIKELIHOOD

This detection is likely environmental noise or a false positive.

POSSIBLE SOURCES:
- Switching power supplies (laptop chargers)
- LCD backlight PWM
- HVAC ultrasonic humidifiers
- Electronic interference

MONITORING: Will alert if this beacon appears at other locations you visit."""
            }
        }
    }

    /**
     * Determine anomaly type based on source and characteristics
     */
    private fun determineAnomalyType(possibleSource: String, isKnownBeacon: Boolean, analysis: BeaconAnalysis): UltrasonicAnomalyType {
        return when {
            // Cross-location detection is always the highest priority
            analysis.followingUser || analysis.isFollowingAcrossLocations ->
                UltrasonicAnomalyType.CROSS_DEVICE_TRACKING

            // Ad tracking beacons (SilverPush, Alphonso, Zapr)
            analysis.matchedSource == KnownBeaconType.SILVERPUSH ||
                analysis.matchedSource == KnownBeaconType.ALPHONSO ||
                analysis.matchedSource == KnownBeaconType.ZAPR ->
                UltrasonicAnomalyType.ADVERTISING_BEACON

            // Retail beacons (Shopkick)
            analysis.matchedSource == KnownBeaconType.SHOPKICK ||
                analysis.sourceCategory == SourceCategory.RETAIL ->
                UltrasonicAnomalyType.RETAIL_BEACON

            // Cross-device linking (LISNR)
            analysis.matchedSource == KnownBeaconType.LISNR ->
                UltrasonicAnomalyType.CROSS_DEVICE_TRACKING

            // Smart TV ACR (Samba TV, Inscape, TVision)
            analysis.matchedSource == KnownBeaconType.SAMBA_TV ||
                analysis.matchedSource == KnownBeaconType.TVISION ->
                UltrasonicAnomalyType.ADVERTISING_BEACON

            // Known beacon from database
            isKnownBeacon ->
                UltrasonicAnomalyType.TRACKING_BEACON

            // Unknown source
            else ->
                UltrasonicAnomalyType.UNKNOWN_ULTRASONIC
        }
    }

    /**
     * Build enriched description for anomaly report
     */
    private fun buildEnrichedDescription(analysis: BeaconAnalysis, freq: Int): String {
        val parts = mutableListOf<String>()

        // Source identification
        parts.add(analysis.probableSourceDescription)

        // What it does
        parts.add("")
        parts.add("What it does: ${analysis.whatItDoes}")

        // Tracking likelihood
        val likelihoodLevel = when {
            analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_HIGH -> "HIGH"
            analysis.trackingLikelihood >= TRACKING_LIKELIHOOD_MEDIUM -> "MEDIUM"
            else -> "LOW"
        }
        parts.add("")
        parts.add("Tracking Likelihood: ${String.format("%.0f", analysis.trackingLikelihood)}% ($likelihoodLevel)")

        // Cross-location warning
        if (analysis.isFollowingAcrossLocations) {
            parts.add("")
            parts.add("WARNING: This beacon has been detected at multiple locations you've visited!")
        }

        // False positive assessment
        if (analysis.falsePositiveLikelihood > 20f) {
            parts.add("")
            parts.add("False Positive Likelihood: ${String.format("%.0f", analysis.falsePositiveLikelihood)}%")
        }

        // Recommended action
        parts.add("")
        parts.add("Recommended Action: ${analysis.recommendedAction}")

        return parts.joinToString("\n")
    }

    /**
     * Build false positive description for suppressed alerts
     */
    private fun buildFalsePositiveDescription(analysis: BeaconAnalysis): String {
        val reasons = mutableListOf<String>()

        if (analysis.isLikelyAmbientNoise) {
            reasons.add("ambient noise pattern detected")
        }
        if (analysis.isLikelyDeviceArtifact) {
            reasons.add("device artifact characteristics")
        }
        if (!analysis.isFrequencyStable) {
            reasons.add("unstable frequency (drifting)")
        }
        if (analysis.amplitudeProfile == AmplitudeProfile.ERRATIC) {
            reasons.add("erratic amplitude")
        }

        val fpSource = identifyFalsePositiveSource(analysis.frequencyHz)
        if (fpSource != null) {
            reasons.add("matches ${fpSource.displayName} range")
        }

        val reasonText = if (reasons.isNotEmpty()) {
            reasons.joinToString(", ")
        } else {
            "multiple noise indicators"
        }

        return "Likely false positive (${String.format("%.0f", analysis.falsePositiveLikelihood)}% FP likelihood): $reasonText"
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

        // False positive indicators
        if (analysis.falsePositiveLikelihood > 30f) {
            parts.add("")
            parts.add("--- FALSE POSITIVE ANALYSIS ---")
            parts.add("FP Likelihood: ${String.format("%.0f", analysis.falsePositiveLikelihood)}%")
            if (analysis.concurrentBeaconCount > 0) {
                parts.add("Concurrent beacons: ${analysis.concurrentBeaconCount}")
            }
            if (analysis.isLikelyAmbientNoise) {
                parts.add("⚠️ Likely ambient noise pattern")
            }
            if (analysis.isLikelyDeviceArtifact) {
                parts.add("⚠️ Likely device artifact")
            }
            analysis.fpIndicators.forEach { parts.add("• $it") }
        }

        return parts.joinToString("\n")
    }

    /**
     * Build contributing factors from analysis
     */
    private fun buildBeaconContributingFactors(analysis: BeaconAnalysis): List<String> {
        val factors = analysis.riskIndicators.toMutableList()
        // Add FP indicators as negative factors if present
        if (analysis.falsePositiveLikelihood > 40f) {
            factors.add("⚠️ ${String.format("%.0f", analysis.falsePositiveLikelihood)}% false positive likelihood")
        }
        return factors
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

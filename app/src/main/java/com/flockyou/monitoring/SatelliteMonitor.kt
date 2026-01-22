package com.flockyou.monitoring

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.SignalStrength as TelephonySignalStrength
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.flockyou.config.NetworkConfig
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.SignalStrength as DetectionSignalStrength
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.Duration

/**
 * SatelliteMonitor - Detects and monitors satellite connectivity for surveillance detection
 * 
 * Supports:
 * - T-Mobile Starlink Direct to Cell (3GPP Release 17, launched July 2025)
 * - Skylo NTN (Pixel 9/10 Satellite SOS)
 * - Generic NB-IoT NTN and NR-NTN detection
 * 
 * Surveillance Detection Heuristics:
 * - Unexpected satellite connections in covered areas
 * - Forced network handoffs to satellite
 * - Unusual NTN parameters suggesting IMSI catcher activity
 * - Satellite connection when terrestrial coverage is available
 */
class SatelliteMonitor(
    private val context: Context,
    private val errorCallback: com.flockyou.service.ScanningService.DetectorCallback? = null
) {
    
    companion object {
        private const val TAG = "SatelliteMonitor"
        
        // T-Mobile Starlink Network Identifiers (launched July 2025)
        val STARLINK_NETWORK_NAMES = setOf(
            "T-Mobile SpaceX",
            "T-Sat+Starlink",
            "Starlink",
            "T-Satellite"
        )
        
        // Skylo NTN Network Identifiers (Pixel 9/10)
        val SKYLO_NETWORK_NAMES = setOf(
            "Skylo",
            "Skylo NTN",
            "Satellite SOS"
        )
        
        // Generic Satellite Indicators
        val SATELLITE_NETWORK_INDICATORS = setOf(
            "SAT",
            "Satellite",
            "NTN",
            "D2D"  // Direct to Device
        )
        
        // 3GPP Release 17 NTN Bands
        object NTNBands {
            // L-band (n253-n255)
            val L_BAND_LOW = 1525  // MHz - Downlink start
            val L_BAND_HIGH = 1559 // MHz - Downlink end
            val L_BAND_UL_LOW = 1626 // MHz - Uplink start
            val L_BAND_UL_HIGH = 1660 // MHz - Uplink end

            // S-band (n256)
            val S_BAND_LOW = 1980  // MHz
            val S_BAND_HIGH = 2010 // MHz
            val S_BAND_UL_LOW = 2170 // MHz
            val S_BAND_UL_HIGH = 2200 // MHz

            // NR ARFCN ranges for NTN bands (3GPP TS 38.101)
            // L-band n253-n255: ARFCN 422000-434000
            // S-band n256: ARFCN 434001-440000
            val NRARFCN_NTN_L_BAND_LOW = 422000
            val NRARFCN_NTN_L_BAND_HIGH = 434000
            val NRARFCN_NTN_S_BAND_LOW = 434001
            val NRARFCN_NTN_S_BAND_HIGH = 440000

            // Check if frequency is in NTN band
            fun isNTNFrequency(freqMHz: Int): Boolean {
                return (freqMHz in L_BAND_LOW..L_BAND_HIGH) ||
                       (freqMHz in L_BAND_UL_LOW..L_BAND_UL_HIGH) ||
                       (freqMHz in S_BAND_LOW..S_BAND_HIGH) ||
                       (freqMHz in S_BAND_UL_LOW..S_BAND_UL_HIGH)
            }

            /**
             * Check if NR ARFCN is within valid NTN band ranges
             */
            fun isValidNtnArfcn(nrarfcn: Int): Boolean {
                return nrarfcn in NRARFCN_NTN_L_BAND_LOW..NRARFCN_NTN_L_BAND_HIGH ||
                       nrarfcn in NRARFCN_NTN_S_BAND_LOW..NRARFCN_NTN_S_BAND_HIGH
            }

            /**
             * Get band name from NRARFCN
             */
            fun getNtnBandFromArfcn(nrarfcn: Int): String? {
                return when (nrarfcn) {
                    in NRARFCN_NTN_L_BAND_LOW..NRARFCN_NTN_L_BAND_HIGH -> "L-band (n253-n255)"
                    in NRARFCN_NTN_S_BAND_LOW..NRARFCN_NTN_S_BAND_HIGH -> "S-band (n256)"
                    else -> null
                }
            }
        }

        // ====================================================================
        // Comprehensive Detection Thresholds and Constants
        // ====================================================================

        object DetectionThresholds {
            // Timing thresholds
            const val MIN_SATELLITE_RTT_MS = 15          // Anything faster is spoofed
            const val LEO_RTT_MAX_MS = 100               // LEO upper bound
            const val MEO_RTT_MAX_MS = 200               // MEO upper bound
            const val GEO_RTT_MAX_MS = 700               // GEO upper bound
            const val MAX_LEO_PASS_DURATION_MS = 15 * 60 * 1000L  // 15 minutes
            const val MIN_HANDOVER_INTERVAL_MS = 30_000  // 30 seconds minimum between handovers
            const val TIMING_ADVANCE_MIN_SATELLITE_KM = 200.0  // Min distance for satellite

            // Signal thresholds
            const val NTN_SIGNAL_MAX_DBM = -80           // Too strong for satellite
            const val NTN_SIGNAL_MIN_DBM = -140          // Too weak to be real
            const val NTN_EXPECTED_RSRP_MIN = -130       // Expected RSRP range
            const val NTN_EXPECTED_RSRP_MAX = -100
            const val MAX_NTN_BANDWIDTH_MHZ = 30         // Max NTN channel bandwidth
            val VALID_SCS_NTN = setOf(15, 30)            // Valid subcarrier spacing kHz

            // Doppler thresholds for LEO (~7.5 km/s velocity)
            const val LEO_DOPPLER_SHIFT_MIN_HZ = 20_000.0
            const val LEO_DOPPLER_SHIFT_MAX_HZ = 50_000.0
            const val DOPPLER_TOLERANCE_PERCENT = 20.0

            // Protocol thresholds
            const val MIN_NTN_DRX_CYCLE_MS = 320         // NTN uses extended DRX
            const val MIN_NTN_PAGING_CYCLE_MS = 1280     // Extended paging for NTN
            const val NTN_HARQ_PROCESSES = 32           // NTN uses up to 32 vs 16 terrestrial
            const val MAX_TERRESTRIAL_HARQ_RTT_MS = 8.0

            // Coverage thresholds
            const val INDOOR_SIGNAL_THRESHOLD_DBM = -85  // If stronger, probably not satellite
            const val MIN_SKY_VIEW_FACTOR = 0.3          // Need at least 30% sky view
            const val EXCELLENT_TERRESTRIAL_DBM = -75    // Excellent terrestrial coverage

            // Behavioral thresholds
            const val TRACKING_CORRELATION_THRESHOLD = 0.7
            const val MAX_IDENTITY_REQUESTS_PER_HOUR = 5
            const val SUSPICIOUS_NTN_AFTER_CALL_WINDOW_MS = 60_000  // 1 minute

            // Time thresholds
            const val MAX_GNSS_NTN_TIME_DIFF_MS = 500    // Half second max difference
            const val STALE_TLE_AGE_HOURS = 24           // TLE older than 24h is stale
        }

        // Known NTN PLMNs (Mobile Country Code + Network Code)
        val KNOWN_NTN_PLMNS = setOf(
            "310260",   // T-Mobile US (Starlink)
            "311490",   // T-Mobile US
            "310410",   // AT&T (AST SpaceMobile)
            "310120",   // Sprint/T-Mobile
            "001001",   // Test PLMN
            "901001",   // Satellite operator reserved
            "901087",   // Satellite operator reserved
        )

        // Provider-specific orbital parameters
        object ProviderOrbitalData {
            // Starlink LEO constellation
            val STARLINK = ProviderOrbitalParams(
                altitudeKm = 540.0 to 570.0,
                orbitalSpeedKph = 27_000.0 to 28_000.0,
                expectedLatencyMs = 20 to 50,
                constellationSize = 6000,
                orbitalPeriodMin = 95.0,
                maxPassDurationMin = 12.0
            )

            // Skylo (uses various GEO/LEO)
            val SKYLO = ProviderOrbitalParams(
                altitudeKm = 500.0 to 2000.0,
                orbitalSpeedKph = 25_000.0 to 28_000.0,
                expectedLatencyMs = 30 to 100,
                constellationSize = 100,
                orbitalPeriodMin = 90.0,
                maxPassDurationMin = 15.0
            )

            // Iridium LEO
            val IRIDIUM = ProviderOrbitalParams(
                altitudeKm = 780.0 to 785.0,
                orbitalSpeedKph = 26_500.0 to 27_500.0,
                expectedLatencyMs = 30 to 50,
                constellationSize = 66,
                orbitalPeriodMin = 100.4,
                maxPassDurationMin = 10.0
            )

            // Globalstar LEO
            val GLOBALSTAR = ProviderOrbitalParams(
                altitudeKm = 1400.0 to 1420.0,
                orbitalSpeedKph = 25_000.0 to 26_000.0,
                expectedLatencyMs = 40 to 80,
                constellationSize = 24,
                orbitalPeriodMin = 114.0,
                maxPassDurationMin = 15.0
            )

            // AST SpaceMobile LEO
            val AST_SPACEMOBILE = ProviderOrbitalParams(
                altitudeKm = 700.0 to 740.0,
                orbitalSpeedKph = 26_500.0 to 27_500.0,
                expectedLatencyMs = 25 to 45,
                constellationSize = 243, // Planned
                orbitalPeriodMin = 99.0,
                maxPassDurationMin = 10.0
            )

            // Inmarsat GEO
            val INMARSAT = ProviderOrbitalParams(
                altitudeKm = 35_780.0 to 35_790.0,
                orbitalSpeedKph = 11_000.0 to 11_100.0,
                expectedLatencyMs = 250 to 600,
                constellationSize = 14,
                orbitalPeriodMin = 1436.0, // ~24 hours
                maxPassDurationMin = Double.MAX_VALUE // Always visible
            )

            fun getForProvider(provider: SatelliteProvider): ProviderOrbitalParams? {
                return when (provider) {
                    SatelliteProvider.STARLINK -> STARLINK
                    SatelliteProvider.SKYLO -> SKYLO
                    SatelliteProvider.IRIDIUM -> IRIDIUM
                    SatelliteProvider.GLOBALSTAR -> GLOBALSTAR
                    SatelliteProvider.AST_SPACEMOBILE -> AST_SPACEMOBILE
                    SatelliteProvider.INMARSAT -> INMARSAT
                    SatelliteProvider.LYNK -> STARLINK // Similar to Starlink
                    SatelliteProvider.UNKNOWN -> null
                }
            }
        }

        data class ProviderOrbitalParams(
            val altitudeKm: Pair<Double, Double>,
            val orbitalSpeedKph: Pair<Double, Double>,
            val expectedLatencyMs: Pair<Int, Int>,
            val constellationSize: Int,
            val orbitalPeriodMin: Double,
            val maxPassDurationMin: Double
        )

        // Device-specific NTN modem requirements
        val SKYLO_CAPABLE_MODEMS = setOf(
            "Exynos 5400",
            "MT T900",
            "MediaTek T900",
            "Dimensity 9400"
        )

        val STARLINK_D2D_COMPATIBLE_BANDS = setOf(
            // T-Mobile PCS bands used for Starlink D2D
            25, 26, 41, 66, 71
        )

        // Commercial launch dates (Unix timestamp milliseconds)
        object ProviderLaunchDates {
            const val STARLINK_D2D = 1719792000000L      // July 2024 beta, Jan 2025 full
            const val SKYLO_PIXEL = 1694563200000L       // Sept 2023 for Pixel
            const val AST_SPACEMOBILE = 1735689600000L   // Expected 2025 commercial
            const val GLOBALSTAR_APPLE = 1668470400000L  // Nov 2022 iPhone 14
        }

        // NTN Coverage regions (simplified)
        object CoverageRegions {
            val STARLINK_D2D = setOf("US", "CA") // Initially US and Canada
            val SKYLO = setOf("US", "CA", "EU", "AU", "JP")
            val GLOBALSTAR_SOS = setOf("US", "CA", "EU", "AU", "NZ", "JP")
        }

        // Valid modem state transitions
        val VALID_MODEM_TRANSITIONS = mapOf(
            SatelliteModemState.UNKNOWN to setOf(SatelliteModemState.IDLE, SatelliteModemState.DISABLED),
            SatelliteModemState.IDLE to setOf(SatelliteModemState.LISTENING, SatelliteModemState.DISABLED),
            SatelliteModemState.LISTENING to setOf(SatelliteModemState.NOT_CONNECTED, SatelliteModemState.CONNECTED, SatelliteModemState.IDLE),
            SatelliteModemState.NOT_CONNECTED to setOf(SatelliteModemState.CONNECTED, SatelliteModemState.LISTENING, SatelliteModemState.IDLE),
            SatelliteModemState.CONNECTED to setOf(SatelliteModemState.DATAGRAM_TRANSFERRING, SatelliteModemState.NOT_CONNECTED, SatelliteModemState.IDLE),
            SatelliteModemState.DATAGRAM_TRANSFERRING to setOf(SatelliteModemState.CONNECTED, SatelliteModemState.DATAGRAM_RETRYING, SatelliteModemState.NOT_CONNECTED),
            SatelliteModemState.DATAGRAM_RETRYING to setOf(SatelliteModemState.DATAGRAM_TRANSFERRING, SatelliteModemState.CONNECTED, SatelliteModemState.NOT_CONNECTED),
            SatelliteModemState.DISABLED to setOf(SatelliteModemState.IDLE)
        )

        // NTN Radio Technologies (from Android SatelliteManager)
        object NTRadioTechnology {
            const val UNKNOWN = 0
            const val NB_IOT_NTN = 1      // 3GPP NB-IoT over NTN
            const val NR_NTN = 2           // 3GPP 5G NR over NTN
            const val EMTC_NTN = 3         // 3GPP eMTC over NTN
            const val PROPRIETARY = 4      // Proprietary (e.g., Globalstar/Apple)
        }
        
        // T-Mobile Starlink Direct to Cell Specifications
        object StarlinkDTC {
            const val ORBITAL_ALTITUDE_KM = 540  // LEO orbit
            const val SATELLITE_COUNT = 650      // As of January 2026
            const val SPEED_MPH = 17000          // Orbital velocity
            const val COVERAGE_AREA_SQ_MILES = 500000  // US coverage
            
            // 3GPP Release 17 characteristics
            const val MAX_CHANNEL_BW_MHZ = 30    // Max NTN channel bandwidth
            const val TYPICAL_LATENCY_MS = 30   // LEO typical latency
            const val GEO_LATENCY_MS = 250      // GEO worst case
            
            // HARQ processes for NTN (increased from 16 to 32)
            const val MAX_HARQ_PROCESSES = 32
        }
        
        // Anomaly thresholds
        const val UNEXPECTED_SATELLITE_THRESHOLD_MS = 5000L
        const val RAPID_HANDOFF_THRESHOLD_MS = 2000L
        const val MIN_SIGNAL_FOR_TERRESTRIAL_DBM = -100

        // Default timing values (can be overridden by updateScanTiming)
        const val DEFAULT_PERIODIC_CHECK_INTERVAL_MS = 3000L  // More frequent checks
        const val DEFAULT_ANOMALY_DETECTION_INTERVAL_MS = 5000L  // More frequent anomaly detection
    }
    
    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }
    
    private var coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Telephony callback reference for proper cleanup
    private var telephonyCallback: TelephonyCallback? = null

    // Configurable timing
    private var periodicCheckIntervalMs: Long = DEFAULT_PERIODIC_CHECK_INTERVAL_MS
    private var anomalyDetectionIntervalMs: Long = DEFAULT_ANOMALY_DETECTION_INTERVAL_MS

    // State flows
    private val _satelliteState = MutableStateFlow(SatelliteConnectionState())
    val satelliteState: StateFlow<SatelliteConnectionState> = _satelliteState.asStateFlow()
    
    private val _anomalies = MutableSharedFlow<SatelliteAnomaly>(replay = 100)
    val anomalies: SharedFlow<SatelliteAnomaly> = _anomalies.asSharedFlow()
    
    // Connection history for pattern detection (thread-safe)
    private val connectionHistory = java.util.Collections.synchronizedList(mutableListOf<SatelliteConnectionEvent>())
    private val maxHistorySize = 1000
    
    // Terrestrial signal baseline
    private var lastTerrestrialSignal: Int? = null
    private var lastTerrestrialTimestamp: Long = 0

    // SatelliteManager for API 31+ (Android 12+)
    // Note: SatelliteManager is in android.telephony.satellite package (API 31+)
    private var satelliteManagerSupported = false
    private var satelliteManagerInstance: Any? = null  // Stored via reflection
    private var currentModemState: SatelliteModemState = SatelliteModemState.UNKNOWN
    private var satelliteEnabled: Boolean? = null
    private var satelliteProvisioned: Boolean? = null
    private var lastRttMeasurementMs: Long? = null
    private var lastNrarfcn: Int? = null
    
    /**
     * Satellite Connection State
     */
    data class SatelliteConnectionState(
        val isConnected: Boolean = false,
        val connectionType: SatelliteConnectionType = SatelliteConnectionType.NONE,
        val networkName: String? = null,
        val operatorName: String? = null,
        val radioTechnology: Int = NTRadioTechnology.UNKNOWN,
        val signalStrength: Int? = null,
        val frequency: Int? = null,
        val isNTNBand: Boolean = false,
        val lastUpdate: Long = System.currentTimeMillis(),
        val provider: SatelliteProvider = SatelliteProvider.UNKNOWN,
        val capabilities: SatelliteCapabilities = SatelliteCapabilities(),
        // New fields for enhanced NTN detection
        val modemState: SatelliteModemState = SatelliteModemState.UNKNOWN,
        val ntnSignalStrength: NtnSignalStrength? = null,
        val nrarfcn: Int? = null,                    // NR ARFCN from CellInfoNr
        val measuredRttMs: Long? = null,             // Network round-trip time
        val estimatedOrbit: OrbitType = OrbitType.UNKNOWN,
        val satelliteManagerSupported: Boolean = false
    )
    
    enum class SatelliteConnectionType {
        NONE,
        T_MOBILE_STARLINK,      // T-Mobile + SpaceX Direct to Cell
        SKYLO_NTN,              // Pixel 9/10 Satellite SOS
        GENERIC_NTN,            // Other 3GPP NTN
        PROPRIETARY,            // Apple/Globalstar style
        UNKNOWN_SATELLITE
    }
    
    enum class SatelliteProvider {
        UNKNOWN,
        STARLINK,           // SpaceX
        SKYLO,              // Pixel partner
        GLOBALSTAR,         // Apple partner
        AST_SPACEMOBILE,    // AT&T partner
        LYNK,               // Other D2D
        IRIDIUM,
        INMARSAT
    }

    /**
     * Satellite modem state from Android SatelliteManager (API 31+)
     */
    enum class SatelliteModemState {
        UNKNOWN,
        IDLE,                    // Modem idle, not actively communicating
        LISTENING,               // Modem scanning for satellites
        NOT_CONNECTED,           // Modem active but not connected
        CONNECTED,               // Connected to satellite
        DATAGRAM_TRANSFERRING,   // Actively sending/receiving data
        DATAGRAM_RETRYING,       // Retrying failed transmission
        DISABLED                 // Modem disabled by user/system
    }

    /**
     * Orbit type estimation based on RTT measurement
     */
    enum class OrbitType(val displayName: String, val expectedRttRangeMs: IntRange) {
        LEO("Low Earth Orbit", 20..80),           // Starlink, OneWeb (~540-1200km)
        MEO("Medium Earth Orbit", 80..150),       // O3b (~8000km)
        GEO("Geostationary", 200..700),           // Traditional GEO (~35,786km)
        UNKNOWN("Unknown", 0..Int.MAX_VALUE),     // Cannot determine
        SPOOFED("Possible Spoof", 0..15)          // RTT too fast for any satellite
    }

    /**
     * NTN signal strength from direct SatelliteManager API
     */
    data class NtnSignalStrength(
        val level: Int,          // 0-4 signal level
        val dbm: Int?            // dBm value if available
    )

    data class SatelliteCapabilities(
        val supportsSMS: Boolean = false,
        val supportsMMS: Boolean = false,
        val supportsVoice: Boolean = false,
        val supportsData: Boolean = false,
        val supportsEmergency: Boolean = false,
        val supportsLocationSharing: Boolean = false,
        val maxMessageLength: Int? = null
    )

    // ========================================================================
    // Extended Data Structures for Comprehensive NTN Detection
    // ========================================================================

    /**
     * Two-Line Element (TLE) data for satellite orbital tracking
     */
    data class TleData(
        val satelliteId: String,
        val satelliteName: String,
        val line1: String,
        val line2: String,
        val epoch: Long,
        // Parsed orbital elements
        val inclination: Double,        // degrees
        val raan: Double,               // Right Ascension of Ascending Node (degrees)
        val eccentricity: Double,
        val argOfPerigee: Double,       // degrees
        val meanAnomaly: Double,        // degrees
        val meanMotion: Double,         // revolutions per day
        val altitude: Double            // km (approximate)
    )

    /**
     * Satellite position at a given time
     */
    data class SatellitePosition(
        val satelliteId: String,
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,           // km
        val azimuth: Double,            // degrees from observer
        val elevation: Double,          // degrees above horizon
        val range: Double,              // km from observer
        val velocity: Double,           // km/s
        val isVisible: Boolean,
        val dopplerShift: Double        // Hz (expected)
    )

    /**
     * Extended signal characteristics for NTN validation
     */
    data class NtnSignalCharacteristics(
        val rsrp: Int?,                         // Reference Signal Received Power (dBm)
        val rsrq: Int?,                         // Reference Signal Received Quality (dB)
        val sinr: Int?,                         // Signal to Interference+Noise Ratio (dB)
        val rssi: Int?,                         // Received Signal Strength Indicator (dBm)
        val cqi: Int?,                          // Channel Quality Indicator
        val timingAdvance: Int?,                // Timing Advance value
        val timingAdvanceMs: Double?,           // Timing Advance in milliseconds
        val estimatedDistance: Double?,         // Estimated distance from TA (km)
        val dopplerMeasured: Double?,           // Measured Doppler shift (Hz)
        val dopplerExpected: Double?,           // Expected Doppler for claimed satellite
        val subcarrierSpacing: Int?,            // kHz (15 or 30 for NTN)
        val channelBandwidth: Int?,             // MHz
        val polarization: Polarization?,
        val multipathIndicator: Double?,        // Multipath severity (0-1)
        val snr: Double?                        // Signal-to-Noise ratio
    )

    enum class Polarization {
        CIRCULAR_LEFT,
        CIRCULAR_RIGHT,
        LINEAR_HORIZONTAL,
        LINEAR_VERTICAL,
        UNKNOWN
    }

    /**
     * Network protocol parameters for NTN validation
     */
    data class NtnProtocolParameters(
        val mcc: String?,                       // Mobile Country Code
        val mnc: String?,                       // Mobile Network Code
        val plmn: String?,                      // Combined PLMN
        val cellId: Long?,                      // Cell Identity
        val tac: Int?,                          // Tracking Area Code
        val pci: Int?,                          // Physical Cell ID
        val earfcn: Int?,                       // E-UTRA ARFCN (for LTE)
        val nrarfcn: Int?,                      // NR ARFCN (for 5G)
        val band: Int?,                         // Band number
        val drxCycle: Int?,                     // DRX cycle in ms
        val pagingCycle: Int?,                  // Paging cycle in ms
        val harqProcesses: Int?,                // Number of HARQ processes
        val harqRttMs: Double?,                 // HARQ round-trip time
        val rachPreambleFormat: Int?,           // RACH preamble format
        val measurementGapConfig: Boolean?,     // Whether measurement gaps configured
        val gnssAssistanceSupported: Boolean?,
        val encryptionAlgorithm: String?,       // NEA0, NEA1, NEA2, NEA3
        val integrityAlgorithm: String?         // NIA0, NIA1, NIA2, NIA3
    )

    /**
     * Location and coverage context
     */
    data class LocationCoverageContext(
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,                  // meters
        val accuracy: Float?,
        val isIndoors: Boolean?,
        val skyViewFactor: Double?,             // 0-1, estimate of sky visibility
        val urbanDensity: UrbanDensity?,
        val terrestrialSignalDbm: Int?,
        val terrestrialNetworkType: String?,
        val hasTerrestrialCoverage: Boolean?,
        val isInProviderCoverageArea: Boolean?,
        val gnssFixAvailable: Boolean?,
        val gnssTime: Long?,
        val networkTime: Long?,
        val timeDiscrepancyMs: Long?
    )

    enum class UrbanDensity {
        RURAL,
        SUBURBAN,
        URBAN,
        DENSE_URBAN,
        UNKNOWN
    }

    /**
     * Behavioral tracking for pattern detection
     */
    data class BehavioralContext(
        val ntnConnectionHistory: List<NtnConnectionEvent>,
        val recentCalls: List<CallEvent>,
        val recentSms: List<SmsEvent>,
        val movementPattern: MovementPattern?,
        val peerDeviceStates: List<PeerDeviceState>?,
        val currentConnectionDuration: Long?,
        val handoverAttempts: Int?,
        val handoverBlockedCount: Int?,
        val ntnCampingDuration: Long?,
        val lastTerrestrialConnection: Long?
    )

    data class NtnConnectionEvent(
        val timestamp: Long,
        val provider: SatelliteProvider,
        val satelliteId: String?,
        val duration: Long?,
        val wasUserInitiated: Boolean,
        val precedingEvent: String?             // What happened before NTN connection
    )

    data class CallEvent(
        val timestamp: Long,
        val isIncoming: Boolean,
        val duration: Long?,
        val wasOnNtn: Boolean
    )

    data class SmsEvent(
        val timestamp: Long,
        val isIncoming: Boolean,
        val wasOnNtn: Boolean,
        val deliveryLatencyMs: Long?
    )

    data class MovementPattern(
        val isStationary: Boolean,
        val averageSpeed: Double?,              // m/s
        val recentLocations: List<Pair<Double, Double>>,
        val ntnConnectionsCorrelateWithMovement: Boolean?
    )

    data class PeerDeviceState(
        val deviceId: String,
        val isOnNtn: Boolean,
        val networkType: String?,
        val signalStrength: Int?,
        val distance: Float?                    // meters from this device
    )

    /**
     * Hardware and modem state context
     */
    data class HardwareContext(
        val modemState: SatelliteModemState,
        val previousModemState: SatelliteModemState?,
        val modemStateTransitions: List<ModemStateTransition>,
        val announcedCapabilities: Set<String>,
        val expectedCapabilities: Set<String>,
        val basebandVersion: String?,
        val expectedBasebandVersion: String?,
        val activeAntennas: List<String>?,
        val txPowerClass: Int?,
        val expectedTxPowerClass: Int?,
        val activeBands: List<Int>?,
        val conflictingBands: List<Int>?
    )

    data class ModemStateTransition(
        val fromState: SatelliteModemState,
        val toState: SatelliteModemState,
        val timestamp: Long,
        val wasValid: Boolean
    )

    /**
     * Provider-specific validation data
     */
    data class ProviderValidation(
        val provider: SatelliteProvider,
        val expectedAltitudeKm: DoubleRange,
        val expectedOrbitalSpeedKph: DoubleRange,
        val expectedLatencyMs: IntRange,
        val expectedConstellationSize: Int,
        val requiredModems: List<String>,
        val requiredBands: List<Int>,
        val launchDate: Long?,                  // Commercial launch date
        val coverageRegions: List<String>
    )

    data class DoubleRange(val start: Double, val end: Double) {
        operator fun contains(value: Double) = value in start..end
    }

    /**
     * Emergency services context
     */
    data class EmergencyContext(
        val isEmergencyCallActive: Boolean,
        val emergencyCallRoute: String?,
        val reportedLocation: Pair<Double, Double>?,
        val actualGpsLocation: Pair<Double, Double>?,
        val locationDiscrepancyMeters: Double?,
        val recentAlerts: List<EmergencyAlert>,
        val isEmergencyBlocked: Boolean?
    )

    data class EmergencyAlert(
        val timestamp: Long,
        val type: String,                       // WEA, CMAS, etc.
        val source: String,
        val isVerified: Boolean
    )

    /**
     * Complete detection context aggregating all data
     */
    data class ComprehensiveDetectionContext(
        val timestamp: Long,
        val connectionState: SatelliteConnectionState,
        val signalCharacteristics: NtnSignalCharacteristics?,
        val protocolParameters: NtnProtocolParameters?,
        val locationContext: LocationCoverageContext?,
        val behavioralContext: BehavioralContext?,
        val hardwareContext: HardwareContext?,
        val providerValidation: ProviderValidation?,
        val emergencyContext: EmergencyContext?,
        val claimedSatellitePosition: SatellitePosition?,
        val calculatedSatellitePosition: SatellitePosition?,
        val tleData: TleData?
    )
    
    /**
     * Satellite Anomaly Detection
     */
    data class SatelliteAnomaly(
        val type: SatelliteAnomalyType,
        val severity: AnomalySeverity,
        val timestamp: Long = System.currentTimeMillis(),
        val description: String,
        val technicalDetails: Map<String, Any> = emptyMap(),
        val recommendations: List<String> = emptyList()
    )
    
    enum class SatelliteAnomalyType {
        // === Original Anomalies ===
        UNEXPECTED_SATELLITE_CONNECTION,    // Connected to satellite when terrestrial available
        FORCED_SATELLITE_HANDOFF,           // Rapid or suspicious handoff to satellite
        SUSPICIOUS_NTN_PARAMETERS,          // Unusual NTN config suggesting spoofing
        UNKNOWN_SATELLITE_NETWORK,          // Unrecognized satellite network
        SATELLITE_IN_COVERED_AREA,          // Satellite used despite good coverage
        RAPID_SATELLITE_SWITCHING,          // Abnormal satellite handoff patterns
        NTN_BAND_MISMATCH,                  // Claimed satellite but wrong frequency
        TIMING_ADVANCE_ANOMALY,             // NTN timing doesn't match claimed orbit
        EPHEMERIS_MISMATCH,                 // Satellite position doesn't match known data
        DOWNGRADE_TO_SATELLITE,             // Forced from better tech to satellite
        RTT_ORBIT_MISMATCH,                 // Measured RTT doesn't match expected orbit
        UNEXPECTED_MODEM_STATE,             // Satellite modem in inconsistent state
        CAPABILITY_MISMATCH,                // Advertised capabilities don't match known provider specs
        NRARFCN_NTN_BAND_INVALID,           // NRARFCN outside valid NTN band ranges

        // === Timing & Latency Anomalies ===
        DOPPLER_SHIFT_MISMATCH,             // LEO Doppler shift missing or incorrect
        HANDOVER_TIMING_IMPOSSIBLE,         // Satellite handover faster than physically possible
        PROPAGATION_DELAY_VARIANCE_WRONG,   // Delay variance doesn't match LEO pass pattern
        HARQ_RETRANSMISSION_TIMING_WRONG,   // HARQ timing terrestrial instead of NTN-extended
        TIMING_ADVANCE_TOO_SMALL,           // TA suggests <200km when claiming satellite

        // === Orbital & Ephemeris Anomalies ===
        SATELLITE_BELOW_HORIZON,            // Claimed satellite is below horizon for GPS location
        WRONG_ORBITAL_PLANE,                // Satellite ID doesn't match expected orbital plane
        PASS_DURATION_EXCEEDED,             // Connection longer than max LEO pass (~15 min)
        ELEVATION_ANGLE_IMPOSSIBLE,         // Claimed elevation incompatible with signal
        TLE_POSITION_MISMATCH,              // Real-time TLE position doesn't match network ephemeris
        SATELLITE_ID_REUSE,                 // Same satellite ID from incompatible locations

        // === Signal & RF Anomalies ===
        SIGNAL_TOO_STRONG,                  // NTN signal stronger than expected (-100 to -130 dBm)
        WRONG_POLARIZATION,                 // Linear polarization instead of circular
        BANDWIDTH_MISMATCH,                 // Channel bandwidth exceeds NTN limits (>30 MHz)
        CARRIER_FREQUENCY_DRIFT_WRONG,      // Doppler drift pattern incorrect for orbit
        MULTIPATH_IN_CLEAR_SKY,             // Multipath when LOS expected
        SUBCARRIER_SPACING_WRONG,           // SCS not 15/30 kHz as required for NTN

        // === Protocol & Network Behavior Anomalies ===
        MIB_SIB_INCONSISTENT,               // System info contains non-NTN parameters
        PLMN_NOT_NTN_REGISTERED,            // PLMN ID not in known NTN operator list
        CELL_ID_FORMAT_WRONG,               // Cell ID format incorrect for NTN
        PAGING_CYCLE_TERRESTRIAL,           // Paging cycle too short for NTN
        DRX_TOO_SHORT,                      // Discontinuous reception cycle not extended
        RACH_PROCEDURE_WRONG,               // Random access using terrestrial timing
        MEASUREMENT_GAP_MISSING,            // No measurement gaps for Doppler/delay tracking
        GNSS_ASSISTANCE_REJECTED,           // Network rejecting required GNSS assistance

        // === Authentication & Security Anomalies ===
        ENCRYPTION_DOWNGRADE,               // NTN attempting weaker/no encryption
        IDENTITY_REQUEST_FLOOD,             // Excessive IMSI/IMEI requests over satellite
        REPLAY_ATTACK_DETECTED,             // Same auth challenge/response seen multiple times
        CERTIFICATE_MISMATCH,               // TLS cert doesn't match known provider
        NULL_CIPHER_OFFERED,                // Network offering null cipher on satellite
        AUTH_REJECT_LOOP,                   // Repeated auth rejects then accepts (probing)
        SUPI_CONCEALMENT_DISABLED,          // 5G NTN not using SUPI concealment

        // === Coverage & Location Anomalies ===
        NTN_IN_FULL_TERRESTRIAL_COVERAGE,   // NTN forced when excellent terrestrial available
        COVERAGE_HOLE_IMPOSSIBLE,           // Claimed hole where mapping shows coverage
        GEOFENCE_VIOLATION,                 // NTN in region without license/coverage
        INDOOR_SATELLITE_CONNECTION,        // Satellite connection with no sky view
        ALTITUDE_INCOMPATIBLE,              // Device altitude prevents satellite visibility
        URBAN_CANYON_SATELLITE,             // Satellite in dense urban with blocked sky

        // === Multi-System Correlation Anomalies ===
        GNSS_NTN_TIME_CONFLICT,             // GNSS time differs from NTN network time
        GNSS_POSITION_COVERAGE_MISMATCH,    // GNSS position outside NTN coverage area
        SIMULTANEOUS_GNSS_JAMMING,          // GNSS jamming during NTN connection
        CELLULAR_NTN_GEOMETRY_IMPOSSIBLE,   // Cell tower + satellite geometry impossible
        WIFI_SATELLITE_CONFLICT,            // WiFi AP location vs satellite visibility

        // === Behavioral & Pattern Anomalies ===
        NTN_TRACKING_PATTERN,               // NTN connections correlate with movement
        FORCED_NTN_AFTER_CALL,              // Forced to NTN after specific call/SMS
        SELECTIVE_NTN_ROUTING,              // Only certain traffic through NTN
        NTN_CAMPING_PERSISTENT,             // Persistently on NTN despite terrestrial
        HANDOVER_BACK_BLOCKED,              // Network blocking return to terrestrial
        PEER_DEVICE_DIVERGENCE,             // Your device on NTN, nearby on terrestrial
        TIME_OF_DAY_VISIBILITY_ANOMALY,     // NTN when satellite shouldn't be visible

        // === Hardware/Modem Anomalies ===
        MODEM_STATE_TRANSITION_IMPOSSIBLE,  // State transition skipped required phases
        CAPABILITY_ANNOUNCEMENT_WRONG,      // Device announcing wrong NTN capabilities
        BASEBAND_FIRMWARE_TAMPERED,         // Baseband version inconsistent
        ANTENNA_CONFIGURATION_WRONG,        // Using wrong antenna for claimed band
        POWER_CLASS_MISMATCH,               // TX power wrong for NTN
        SIMULTANEOUS_BAND_CONFLICT,         // Incompatible band usage

        // === Provider-Specific Anomalies ===
        STARLINK_ORBITAL_PARAMS_WRONG,      // Starlink but wrong speed/altitude
        SKYLO_MODEM_MISSING,                // Claimed Skylo without correct modem
        IRIDIUM_CONSTELLATION_MISMATCH,     // Iridium geometry doesn't match 66-sat
        GLOBALSTAR_BAND_WRONG,              // Globalstar not on S-band
        AST_SPACEMOBILE_PREMATURE,          // AST connection before commercial launch
        PROVIDER_CAPABILITY_MISMATCH,       // Provider can't support claimed feature

        // === Message/Data Anomalies ===
        SMS_ROUTING_SUSPICIOUS,             // SMS via NTN when terrestrial faster
        DATAGRAM_SIZE_EXCEEDED,             // Datagram exceeds satellite limits
        MESSAGE_LATENCY_WRONG,              // Delivery time inconsistent with satellite
        ACK_TIMING_TERRESTRIAL,             // ACK timing matches terrestrial, not satellite
        STORE_FORWARD_MISSING,              // No store-forward during satellite transit

        // === Emergency Services Anomalies ===
        SOS_REDIRECT_SUSPICIOUS,            // Emergency SOS through unexpected path
        E911_LOCATION_INJECTION,            // Emergency call location doesn't match GPS
        EMERGENCY_CALL_BLOCKED,             // Emergency blocked while claiming satellite
        FAKE_EMERGENCY_ALERT                // WEA/CMAS from unverified satellite source
    }
    
    enum class AnomalySeverity {
        INFO,       // Informational
        LOW,        // Unusual but possibly legitimate
        MEDIUM,     // Suspicious activity
        HIGH,       // Likely surveillance attempt
        CRITICAL    // Confirmed anomaly
    }
    
    data class SatelliteConnectionEvent(
        val timestamp: Long,
        val connectionType: SatelliteConnectionType,
        val networkName: String?,
        val wasExpected: Boolean,
        val terrestrialSignalAtSwitch: Int?,
        val duration: Long? = null
    )
    
    /**
     * Start satellite monitoring
     */
    @RequiresPermission(allOf = [
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
    fun startMonitoring() {
        Log.i(TAG, "Starting Satellite Monitor")

        try {
            // Register telephony callback for network changes
            registerTelephonyCallback()

            // Start periodic satellite state checks
            startPeriodicChecks()

            // Start anomaly detection
            startAnomalyDetection()

            // Check for satellite support on device
            checkDeviceSatelliteSupport()

            errorCallback?.onDetectorStarted(com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_SATELLITE)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting satellite monitoring", e)
            errorCallback?.onError(
                com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_SATELLITE,
                "Failed to start monitoring: ${e.message}",
                recoverable = true
            )
        }
    }
    
    /**
     * Update scan timing configuration.
     * @param intervalSeconds Time between satellite state checks (5-60 seconds)
     */
    fun updateScanTiming(intervalSeconds: Int) {
        periodicCheckIntervalMs = (intervalSeconds.coerceIn(5, 60) * 1000L)
        anomalyDetectionIntervalMs = (intervalSeconds.coerceIn(5, 60) * 2 * 1000L) // Anomaly check at 2x interval
        Log.d(TAG, "Updated scan timing: periodic=${periodicCheckIntervalMs}ms, anomaly=${anomalyDetectionIntervalMs}ms")
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping Satellite Monitor")

        try {
            // Unregister telephony callback to prevent memory leak
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                    telephonyCallback = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering telephony callback", e)
        }

        // Cancel and recreate scope for potential restart
        coroutineScope.cancel()
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        errorCallback?.onDetectorStopped(com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_SATELLITE)
    }
    
    /**
     * Register telephony callback
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Unregister existing callback if any
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }

            val callback = object : TelephonyCallback(),
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.SignalStrengthsListener {

                override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                    handleDisplayInfoChange(displayInfo)
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    handleServiceStateChange(serviceState)
                }

                override fun onSignalStrengthsChanged(signalStrength: TelephonySignalStrength) {
                    handleSignalStrengthChange(signalStrength)
                }
            }

            // Store reference for cleanup
            telephonyCallback = callback

            telephonyManager.registerTelephonyCallback(
                context.mainExecutor,
                callback
            )
        }
    }
    
    /**
     * Handle display info changes - primary satellite detection
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun handleDisplayInfoChange(displayInfo: TelephonyDisplayInfo) {
        coroutineScope.launch {
            val networkType = displayInfo.networkType

            // Check for satellite indicators
            val operatorName = telephonyManager.networkOperatorName ?: ""
            val simOperator = telephonyManager.simOperatorName ?: ""
            
            val connectionType = detectSatelliteConnectionType(operatorName, simOperator)
            val wasConnectedBefore = _satelliteState.value.isConnected
            
            if (connectionType != SatelliteConnectionType.NONE) {
                val newState = SatelliteConnectionState(
                    isConnected = true,
                    connectionType = connectionType,
                    networkName = operatorName,
                    operatorName = simOperator,
                    radioTechnology = mapNetworkTypeToNTN(networkType),
                    provider = detectProvider(operatorName),
                    capabilities = getCapabilitiesForProvider(connectionType)
                )
                
                _satelliteState.value = newState
                
                // Log connection event
                recordConnectionEvent(newState, wasConnectedBefore)
                
                // Check for anomalies
                if (!wasConnectedBefore) {
                    checkForConnectionAnomaly(newState)
                }
            } else if (wasConnectedBefore) {
                // Disconnected from satellite
                _satelliteState.value = SatelliteConnectionState(isConnected = false)
                
                // Update last terrestrial signal
                updateTerrestrialBaseline()
            }
        }
    }
    
    /**
     * Handle service state changes
     */
    @SuppressLint("MissingPermission")
    private fun handleServiceStateChange(serviceState: ServiceState) {
        coroutineScope.launch {
            val operatorName = serviceState.operatorAlphaLong ?: ""
            
            // Check for NTN-specific service state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val networkRegInfo = serviceState.networkRegistrationInfoList
                
                networkRegInfo.forEach { regInfo ->
                    // Check for satellite access network
                    checkNetworkRegistrationForSatellite(regInfo)
                }
            }
            
            // Detect satellite by operator name
            val connectionType = detectSatelliteConnectionType(operatorName, "")
            if (connectionType != SatelliteConnectionType.NONE) {
                Log.i(TAG, "Satellite detected via ServiceState: $operatorName")
            }
        }
    }
    
    /**
     * Handle signal strength changes
     */
    private fun handleSignalStrengthChange(signalStrength: TelephonySignalStrength) {
        coroutineScope.launch {
            val level = signalStrength.level
            
            // If connected to satellite, update signal
            if (_satelliteState.value.isConnected) {
                _satelliteState.value = _satelliteState.value.copy(
                    signalStrength = level,
                    lastUpdate = System.currentTimeMillis()
                )
            } else {
                // Track terrestrial signal for anomaly detection
                val currentDbm = getBestSignalDbm(signalStrength)
                if (currentDbm != null && currentDbm > MIN_SIGNAL_FOR_TERRESTRIAL_DBM) {
                    lastTerrestrialSignal = currentDbm
                    lastTerrestrialTimestamp = System.currentTimeMillis()
                }
            }
        }
    }
    
    /**
     * Detect satellite connection type from network name
     */
    private fun detectSatelliteConnectionType(
        operatorName: String,
        simOperator: String
    ): SatelliteConnectionType {
        val combinedName = "$operatorName $simOperator".uppercase()
        
        // T-Mobile Starlink
        if (STARLINK_NETWORK_NAMES.any { combinedName.contains(it.uppercase()) }) {
            return SatelliteConnectionType.T_MOBILE_STARLINK
        }
        
        // Skylo NTN (Pixel)
        if (SKYLO_NETWORK_NAMES.any { combinedName.contains(it.uppercase()) }) {
            return SatelliteConnectionType.SKYLO_NTN
        }
        
        // Generic satellite indicators
        if (SATELLITE_NETWORK_INDICATORS.any { combinedName.contains(it.uppercase()) }) {
            return SatelliteConnectionType.GENERIC_NTN
        }
        
        return SatelliteConnectionType.NONE
    }
    
    /**
     * Detect satellite provider
     */
    private fun detectProvider(networkName: String): SatelliteProvider {
        val name = networkName.uppercase()
        return when {
            name.contains("STARLINK") || name.contains("SPACEX") -> SatelliteProvider.STARLINK
            name.contains("SKYLO") -> SatelliteProvider.SKYLO
            name.contains("GLOBALSTAR") -> SatelliteProvider.GLOBALSTAR
            name.contains("AST") -> SatelliteProvider.AST_SPACEMOBILE
            name.contains("LYNK") -> SatelliteProvider.LYNK
            name.contains("IRIDIUM") -> SatelliteProvider.IRIDIUM
            name.contains("INMARSAT") -> SatelliteProvider.INMARSAT
            else -> SatelliteProvider.UNKNOWN
        }
    }
    
    /**
     * Map network type to NTN radio technology
     */
    private fun mapNetworkTypeToNTN(networkType: Int): Int {
        // This is a heuristic since Android doesn't directly expose NTN type
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> NTRadioTechnology.NR_NTN
            TelephonyManager.NETWORK_TYPE_LTE -> NTRadioTechnology.EMTC_NTN
            else -> NTRadioTechnology.NB_IOT_NTN
        }
    }
    
    /**
     * Get capabilities based on provider
     */
    private fun getCapabilitiesForProvider(type: SatelliteConnectionType): SatelliteCapabilities {
        return when (type) {
            SatelliteConnectionType.T_MOBILE_STARLINK -> SatelliteCapabilities(
                supportsSMS = true,
                supportsMMS = true,       // Added late 2025
                supportsVoice = false,    // Coming soon
                supportsData = false,     // Limited apps only
                supportsEmergency = true,
                supportsLocationSharing = true,
                maxMessageLength = 160
            )
            SatelliteConnectionType.SKYLO_NTN -> SatelliteCapabilities(
                supportsSMS = true,
                supportsMMS = false,
                supportsVoice = false,
                supportsData = false,
                supportsEmergency = true,
                supportsLocationSharing = true,
                maxMessageLength = 140
            )
            else -> SatelliteCapabilities(
                supportsSMS = true,
                supportsEmergency = true
            )
        }
    }
    
    /**
     * Check network registration for satellite indicators
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkNetworkRegistrationForSatellite(regInfo: NetworkRegistrationInfo) {
        val accessNetworkTech = regInfo.accessNetworkTechnology
        val domain = regInfo.domain
        
        // NTN would typically show as specific access network technology
        // This is where we'd check for NTN-specific indicators
        
        // Check available services
        val availableServices = regInfo.availableServices
        
        Log.d(TAG, "Network Registration: tech=$accessNetworkTech, domain=$domain, services=$availableServices")
    }
    
    /**
     * Record connection event for pattern analysis
     */
    @Suppress("UNUSED_PARAMETER")
    private fun recordConnectionEvent(state: SatelliteConnectionState, wasConnectedBefore: Boolean) {
        val event = SatelliteConnectionEvent(
            timestamp = System.currentTimeMillis(),
            connectionType = state.connectionType,
            networkName = state.networkName,
            wasExpected = isExpectedConnection(),
            terrestrialSignalAtSwitch = lastTerrestrialSignal
        )
        
        // Thread-safe list handles synchronization internally
        connectionHistory.add(event)
        if (connectionHistory.size > maxHistorySize) {
            connectionHistory.removeAt(0)
        }
    }
    
    /**
     * Check if satellite connection was expected
     */
    private fun isExpectedConnection(): Boolean {
        // Connection is unexpected if:
        // 1. We had good terrestrial signal recently
        // 2. We're in a typically covered area
        // 3. No user-initiated satellite action
        
        val timeSinceGoodTerrestrial = System.currentTimeMillis() - lastTerrestrialTimestamp
        val hadRecentGoodSignal = lastTerrestrialSignal?.let { 
            it > MIN_SIGNAL_FOR_TERRESTRIAL_DBM && timeSinceGoodTerrestrial < UNEXPECTED_SATELLITE_THRESHOLD_MS
        } ?: false
        
        return !hadRecentGoodSignal
    }
    
    /**
     * Check for connection anomalies
     */
    private suspend fun checkForConnectionAnomaly(state: SatelliteConnectionState) {
        // Anomaly 1: Unexpected satellite when terrestrial available
        if (lastTerrestrialSignal != null && 
            lastTerrestrialSignal!! > MIN_SIGNAL_FOR_TERRESTRIAL_DBM &&
            System.currentTimeMillis() - lastTerrestrialTimestamp < UNEXPECTED_SATELLITE_THRESHOLD_MS) {
            
            _anomalies.emit(SatelliteAnomaly(
                type = SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION,
                severity = AnomalySeverity.MEDIUM,
                description = "Device switched to satellite (${state.networkName}) despite good terrestrial signal (${lastTerrestrialSignal} dBm)",
                technicalDetails = mapOf(
                    "lastTerrestrialSignal" to (lastTerrestrialSignal ?: Int.MIN_VALUE),
                    "timeSinceSwitch" to (System.currentTimeMillis() - lastTerrestrialTimestamp),
                    "satelliteNetwork" to (state.networkName ?: "Unknown"),
                    "connectionType" to state.connectionType.name
                ),
                recommendations = listOf(
                    "This could indicate a cell site simulator forcing satellite fallback",
                    "Monitor for other cellular anomalies",
                    "Consider moving to a different location",
                    "Check if satellite mode was manually enabled"
                )
            ))
        }
        
        // Anomaly 2: Unknown satellite network
        if (state.provider == SatelliteProvider.UNKNOWN && state.networkName != null) {
            _anomalies.emit(SatelliteAnomaly(
                type = SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK,
                severity = AnomalySeverity.HIGH,
                description = "Connected to unrecognized satellite network: ${state.networkName}",
                technicalDetails = mapOf(
                    "networkName" to state.networkName,
                    "radioTechnology" to state.radioTechnology
                ),
                recommendations = listOf(
                    "Unknown satellite networks could be spoofed",
                    "Legitimate satellites should identify as known providers",
                    "Consider disabling cellular and using WiFi only",
                    "Report this network to security researchers"
                )
            ))
        }
        
        // Anomaly 3: Rapid switching pattern
        checkRapidSwitchingPattern()
    }
    
    /**
     * Check for rapid switching anomaly
     */
    private suspend fun checkRapidSwitchingPattern() {
        // Create a snapshot for thread-safe iteration
        val historySnapshot = connectionHistory.toList()
        val recentEvents = historySnapshot
            .filter { System.currentTimeMillis() - it.timestamp < 60000 } // Last minute

        if (recentEvents.size >= 3) {
            // Multiple satellite connections in short time
            _anomalies.emit(SatelliteAnomaly(
                type = SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING,
                severity = AnomalySeverity.MEDIUM,
                description = "Detected ${recentEvents.size} satellite connection changes in the last minute",
                technicalDetails = mapOf(
                    "eventCount" to recentEvents.size,
                    "events" to recentEvents.map { "${it.connectionType}@${it.timestamp}" }
                ),
                recommendations = listOf(
                    "Rapid switching could indicate interference or jamming",
                    "May be caused by moving through coverage boundary",
                    "Consider staying in one location to observe pattern"
                )
            ))
        }
    }
    
    /**
     * Update terrestrial baseline
     */
    @SuppressLint("MissingPermission")
    private fun updateTerrestrialBaseline() {
        coroutineScope.launch {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

                    val signalStrength = telephonyManager.signalStrength
                    signalStrength?.let {
                        val dbm = getBestSignalDbm(it)
                        if (dbm != null && dbm > MIN_SIGNAL_FOR_TERRESTRIAL_DBM) {
                            lastTerrestrialSignal = dbm
                            lastTerrestrialTimestamp = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating terrestrial baseline", e)
            }
        }
    }
    
    /**
     * Get best signal strength in dBm
     */
    private fun getBestSignalDbm(signalStrength: TelephonySignalStrength): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths
                .mapNotNull { it.dbm.takeIf { dbm -> dbm != Int.MAX_VALUE && dbm != Int.MIN_VALUE } }
                .maxOrNull()
        } else {
            null
        }
    }
    
    /**
     * Start periodic satellite state checks
     */
    @SuppressLint("MissingPermission")
    private fun startPeriodicChecks() {
        coroutineScope.launch {
            while (isActive) {
                try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED) {
                        checkCurrentState()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic check", e)
                }
                delay(periodicCheckIntervalMs)
            }
        }
    }
    
    /**
     * Check current satellite state
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun checkCurrentState() {
        val operatorName = telephonyManager.networkOperatorName ?: ""
        val connectionType = detectSatelliteConnectionType(operatorName, "")

        if (connectionType != SatelliteConnectionType.NONE && !_satelliteState.value.isConnected) {
            // Satellite detected but not tracked - update state
            _satelliteState.value = SatelliteConnectionState(
                isConnected = true,
                connectionType = connectionType,
                networkName = operatorName,
                provider = detectProvider(operatorName),
                capabilities = getCapabilitiesForProvider(connectionType)
            )
        }

        // Report successful check for health monitoring
        errorCallback?.onScanSuccess(com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_SATELLITE)
    }
    
    /**
     * Start anomaly detection coroutine
     */
    private fun startAnomalyDetection() {
        coroutineScope.launch {
            // Monitor for timing-based anomalies
            while (isActive) {
                delay(anomalyDetectionIntervalMs)
                
                if (_satelliteState.value.isConnected) {
                    performTimingAnalysis()
                }
            }
        }
    }
    
    /**
     * Perform timing analysis for NTN anomaly detection
     */
    private suspend fun performTimingAnalysis() {
        val state = _satelliteState.value

        // Extract NRARFCN from current cell info
        val nrarfcn = extractNrArfcn()
        if (nrarfcn != null) {
            lastNrarfcn = nrarfcn
            _satelliteState.value = state.copy(nrarfcn = nrarfcn)

            // Validate NRARFCN against NTN bands
            if (!NTNBands.isValidNtnArfcn(nrarfcn) && state.isConnected) {
                emitNrarfcnAnomaly(nrarfcn)
            }
        }

        // Measure network RTT for orbit validation
        val rttMs = measureNetworkRtt()
        if (rttMs != null) {
            lastRttMeasurementMs = rttMs
            val estimatedOrbit = estimateOrbitFromRtt(rttMs)

            _satelliteState.value = _satelliteState.value.copy(
                measuredRttMs = rttMs,
                estimatedOrbit = estimatedOrbit
            )

            // Check for RTT/orbit mismatch if connected to satellite
            if (state.isConnected) {
                checkRttOrbitMismatch(rttMs, state.provider)
            }
        }

        // Provider-specific timing validation
        when (state.provider) {
            SatelliteProvider.STARLINK -> {
                // Starlink LEO should have consistent timing (20-80ms)
                if (rttMs != null && (rttMs < 15 || rttMs > 100)) {
                    Log.w(TAG, "Starlink timing anomaly: RTT=${rttMs}ms (expected 20-80ms)")
                }
            }
            SatelliteProvider.SKYLO -> {
                // Skylo uses various satellites, expect LEO-MEO timing
            }
            else -> {}
        }
    }

    /**
     * Extract NR ARFCN from CellInfoNr when on 5G NR
     */
    @SuppressLint("MissingPermission")
    private fun extractNrArfcn(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                return null
            }

            @Suppress("DEPRECATION")
            val cellInfoList = telephonyManager.allCellInfo ?: return null

            cellInfoList
                .filterIsInstance<CellInfoNr>()
                .firstOrNull { it.isRegistered }
                ?.let { cellInfoNr ->
                    val cellIdentity = cellInfoNr.cellIdentity as? CellIdentityNr
                    cellIdentity?.nrarfcn?.takeIf { it != Int.MAX_VALUE && it > 0 }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting NRARFCN", e)
            null
        }
    }

    /**
     * Measure network round-trip time using HTTP HEAD request
     * Uses multiple measurements and returns median for accuracy
     */
    private suspend fun measureNetworkRtt(): Long? = withContext(Dispatchers.IO) {
        try {
            val measurements = mutableListOf<Long>()
            // Use configurable DNS check URLs for OEM customization
            val endpoints = NetworkConfig.DNS_CHECK_URLS

            // Take 3 measurements with different endpoints for reliability
            for (i in 0 until 3) {
                val endpoint = endpoints[i % endpoints.size]
                val rtt = measureSingleRtt(endpoint)
                if (rtt != null) {
                    measurements.add(rtt)
                }
                delay(100) // Small delay between measurements
            }

            if (measurements.isEmpty()) return@withContext null

            // Return median of measurements
            measurements.sorted().let { sorted ->
                sorted[sorted.size / 2]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error measuring network RTT", e)
            null
        }
    }

    /**
     * Measure single RTT to an endpoint
     */
    private fun measureSingleRtt(endpoint: String): Long? {
        return try {
            val start = SystemClock.elapsedRealtimeNanos()
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 1000
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode in 200..399) {
                val end = SystemClock.elapsedRealtimeNanos()
                (end - start) / 1_000_000 // Convert to milliseconds
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Estimate orbit type from measured RTT
     */
    private fun estimateOrbitFromRtt(rttMs: Long): OrbitType {
        return when {
            rttMs < 15 -> OrbitType.SPOOFED   // Too fast for any satellite
            rttMs < 100 -> OrbitType.LEO      // Starlink, OneWeb (20-80ms typical)
            rttMs < 200 -> OrbitType.MEO      // O3b and similar
            rttMs < 700 -> OrbitType.GEO      // Traditional GEO satellites
            else -> OrbitType.UNKNOWN
        }
    }

    /**
     * Get expected orbit type for a satellite provider
     */
    private fun getExpectedOrbit(provider: SatelliteProvider): OrbitType {
        return when (provider) {
            SatelliteProvider.STARLINK -> OrbitType.LEO
            SatelliteProvider.SKYLO -> OrbitType.LEO
            SatelliteProvider.GLOBALSTAR -> OrbitType.LEO
            SatelliteProvider.AST_SPACEMOBILE -> OrbitType.LEO
            SatelliteProvider.LYNK -> OrbitType.LEO
            SatelliteProvider.IRIDIUM -> OrbitType.LEO
            SatelliteProvider.INMARSAT -> OrbitType.GEO
            SatelliteProvider.UNKNOWN -> OrbitType.UNKNOWN
        }
    }

    /**
     * Check for RTT/orbit mismatch anomaly
     */
    private suspend fun checkRttOrbitMismatch(measuredRtt: Long, claimedProvider: SatelliteProvider) {
        val estimatedOrbit = estimateOrbitFromRtt(measuredRtt)
        val expectedOrbit = getExpectedOrbit(claimedProvider)

        // Check for potential spoofing or mismatch
        val isAnomaly = when {
            // RTT too fast for any satellite - likely spoofing
            estimatedOrbit == OrbitType.SPOOFED -> true
            // LEO provider but GEO-like timing
            expectedOrbit == OrbitType.LEO && estimatedOrbit == OrbitType.GEO -> true
            // GEO provider but LEO-like timing (unusual but less suspicious)
            expectedOrbit == OrbitType.GEO && estimatedOrbit == OrbitType.LEO -> false
            else -> false
        }

        if (isAnomaly) {
            val severity = if (estimatedOrbit == OrbitType.SPOOFED) {
                AnomalySeverity.HIGH
            } else {
                AnomalySeverity.MEDIUM
            }

            _anomalies.emit(SatelliteAnomaly(
                type = SatelliteAnomalyType.RTT_ORBIT_MISMATCH,
                severity = severity,
                description = "Network timing (${measuredRtt}ms) inconsistent with claimed ${claimedProvider.name} orbit",
                technicalDetails = mapOf(
                    "measured_rtt_ms" to measuredRtt,
                    "estimated_orbit" to estimatedOrbit.displayName,
                    "expected_orbit" to expectedOrbit.displayName,
                    "provider" to claimedProvider.name
                ),
                recommendations = listOf(
                    "RTT of ${measuredRtt}ms suggests ${estimatedOrbit.displayName}",
                    if (estimatedOrbit == OrbitType.SPOOFED) {
                        "Timing too fast for satellite - possible ground-based spoofing"
                    } else {
                        "Verify you are actually connected to satellite"
                    },
                    "Consider disabling cellular temporarily",
                    "Check satellite connection status in device settings"
                )
            ))
        }
    }

    /**
     * Emit NRARFCN band mismatch anomaly
     */
    private suspend fun emitNrarfcnAnomaly(nrarfcn: Int) {
        _anomalies.emit(SatelliteAnomaly(
            type = SatelliteAnomalyType.NRARFCN_NTN_BAND_INVALID,
            severity = AnomalySeverity.MEDIUM,
            description = "Connected satellite network using non-NTN frequency band (ARFCN: $nrarfcn)",
            technicalDetails = mapOf(
                "nrarfcn" to nrarfcn,
                "expected_l_band_range" to "${NTNBands.NRARFCN_NTN_L_BAND_LOW}-${NTNBands.NRARFCN_NTN_L_BAND_HIGH}",
                "expected_s_band_range" to "${NTNBands.NRARFCN_NTN_S_BAND_LOW}-${NTNBands.NRARFCN_NTN_S_BAND_HIGH}"
            ),
            recommendations = listOf(
                "Legitimate NTN uses L-band (n253-n255) or S-band (n256)",
                "This could indicate a misconfigured or spoofed connection",
                "Monitor other network indicators for anomalies"
            )
        ))
    }
    
    /**
     * Check device satellite support using SatelliteManager and known device models
     */
    @SuppressLint("MissingPermission")
    private fun checkDeviceSatelliteSupport() {
        val hasSatelliteFeature = context.packageManager.hasSystemFeature(
            "android.hardware.telephony.satellite"
        )

        val deviceModel = Build.MODEL.uppercase()
        val manufacturer = Build.MANUFACTURER.uppercase()

        // Known satellite-capable devices
        val isPixel9Or10 = deviceModel.contains("PIXEL 9") || deviceModel.contains("PIXEL 10")
        val isSamsungS24Plus = deviceModel.contains("SM-S92") || deviceModel.contains("SM-S93") ||
                               deviceModel.contains("SM-S91") || deviceModel.contains("S24") ||
                               deviceModel.contains("S25")

        Log.i(TAG, "=== Device Satellite Support Check ===")
        Log.i(TAG, "  Device: $manufacturer $deviceModel")
        Log.i(TAG, "  API Level: ${Build.VERSION.SDK_INT}")
        Log.i(TAG, "  System feature 'android.hardware.telephony.satellite': $hasSatelliteFeature")
        Log.i(TAG, "  Known Pixel 9/10: $isPixel9Or10")
        Log.i(TAG, "  Known Samsung S24+: $isSamsungS24Plus")

        // Try to access SatelliteManager on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Use reflection to check for SatelliteManager since it may not be available on all builds
                val satelliteManagerClass = Class.forName("android.telephony.satellite.SatelliteManager")
                val getServiceMethod = context.javaClass.getMethod("getSystemService", Class::class.java)
                val satelliteManager = getServiceMethod.invoke(context, satelliteManagerClass)

                if (satelliteManager != null) {
                    Log.i(TAG, "  SatelliteManager instance: AVAILABLE")
                    satelliteManagerInstance = satelliteManager

                    // List all available methods for debugging
                    val methods = satelliteManagerClass.methods.map { it.name }.distinct().sorted()
                    Log.i(TAG, "  Available SatelliteManager methods:")
                    methods.forEach { method ->
                        Log.d(TAG, "    - $method")
                    }

                    satelliteManagerSupported = true

                    // Try to query satellite status
                    initializeSatelliteManagerCallbacks(satelliteManager, satelliteManagerClass)

                } else {
                    Log.i(TAG, "  SatelliteManager instance: NULL")
                    satelliteManagerSupported = hasSatelliteFeature || isPixel9Or10 || isSamsungS24Plus
                }
            } catch (e: ClassNotFoundException) {
                Log.i(TAG, "  SatelliteManager class: NOT FOUND (expected on some builds)")
                satelliteManagerSupported = hasSatelliteFeature || isPixel9Or10 || isSamsungS24Plus
            } catch (e: Exception) {
                Log.e(TAG, "  Error checking SatelliteManager: ${e.javaClass.simpleName}: ${e.message}")
                satelliteManagerSupported = hasSatelliteFeature || isPixel9Or10 || isSamsungS24Plus
            }
        } else {
            Log.i(TAG, "  API level ${Build.VERSION.SDK_INT} < 31, SatelliteManager not available")
            satelliteManagerSupported = isPixel9Or10
        }

        Log.i(TAG, "  => FINAL satelliteManagerSupported = $satelliteManagerSupported")
        Log.i(TAG, "========================================")

        // Update state with satellite support info
        _satelliteState.update { it.copy(satelliteManagerSupported = satelliteManagerSupported) }
    }

    /**
     * Initialize SatelliteManager callbacks to receive modem state updates
     */
    @SuppressLint("MissingPermission")
    private fun initializeSatelliteManagerCallbacks(satelliteManager: Any, managerClass: Class<*>) {
        val executor = context.mainExecutor

        // Try to call requestIsEnabled to check if satellite is enabled
        tryQuerySatelliteEnabled(satelliteManager, managerClass, executor)

        // Try to call requestIsProvisioned to check provisioning status
        tryQuerySatelliteProvisioned(satelliteManager, managerClass, executor)

        // Try to call requestIsSupported
        tryQuerySatelliteSupported(satelliteManager, managerClass, executor)

        // Try to register for modem state changes
        tryRegisterModemStateCallback(satelliteManager, managerClass, executor)

        // Try to get satellite capabilities
        tryQuerySatelliteCapabilities(satelliteManager, managerClass, executor)
    }

    private fun tryQuerySatelliteEnabled(manager: Any, managerClass: Class<*>, executor: java.util.concurrent.Executor) {
        try {
            // Look for requestIsEnabled or requestIsSatelliteEnabled method
            val method = managerClass.methods.find {
                it.name == "requestIsEnabled" || it.name == "requestIsSatelliteEnabled"
            }
            if (method != null) {
                Log.i(TAG, "  Calling ${method.name}...")
                // Create OutcomeReceiver using reflection
                val outcomeReceiverClass = Class.forName("android.os.OutcomeReceiver")
                val receiver = java.lang.reflect.Proxy.newProxyInstance(
                    outcomeReceiverClass.classLoader,
                    arrayOf(outcomeReceiverClass)
                ) { _, proxyMethod, args ->
                    when (proxyMethod.name) {
                        "onResult" -> {
                            val result = args?.get(0)
                            Log.i(TAG, "  Satellite enabled: $result")
                            satelliteEnabled = result as? Boolean
                            updateModemStateInUI()
                        }
                        "onError" -> {
                            val error = args?.get(0)
                            Log.w(TAG, "  requestIsEnabled error: $error")
                        }
                    }
                    null
                }
                method.invoke(manager, executor, receiver)
            } else {
                Log.d(TAG, "  requestIsEnabled method not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Error querying satellite enabled: ${e.message}")
        }
    }

    private fun tryQuerySatelliteProvisioned(manager: Any, managerClass: Class<*>, executor: java.util.concurrent.Executor) {
        try {
            val method = managerClass.methods.find {
                it.name == "requestIsProvisioned" || it.name == "requestIsSatelliteProvisioned"
            }
            if (method != null) {
                Log.i(TAG, "  Calling ${method.name}...")
                val outcomeReceiverClass = Class.forName("android.os.OutcomeReceiver")
                val receiver = java.lang.reflect.Proxy.newProxyInstance(
                    outcomeReceiverClass.classLoader,
                    arrayOf(outcomeReceiverClass)
                ) { _, proxyMethod, args ->
                    when (proxyMethod.name) {
                        "onResult" -> {
                            val result = args?.get(0)
                            Log.i(TAG, "  Satellite provisioned: $result")
                            satelliteProvisioned = result as? Boolean
                            updateModemStateInUI()
                        }
                        "onError" -> {
                            val error = args?.get(0)
                            Log.w(TAG, "  requestIsProvisioned error: $error")
                        }
                    }
                    null
                }
                method.invoke(manager, executor, receiver)
            } else {
                Log.d(TAG, "  requestIsProvisioned method not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Error querying satellite provisioned: ${e.message}")
        }
    }

    private fun tryQuerySatelliteSupported(manager: Any, managerClass: Class<*>, executor: java.util.concurrent.Executor) {
        try {
            val method = managerClass.methods.find {
                it.name == "requestIsSupported" || it.name == "requestIsSatelliteSupported"
            }
            if (method != null) {
                Log.i(TAG, "  Calling ${method.name}...")
                val outcomeReceiverClass = Class.forName("android.os.OutcomeReceiver")
                val receiver = java.lang.reflect.Proxy.newProxyInstance(
                    outcomeReceiverClass.classLoader,
                    arrayOf(outcomeReceiverClass)
                ) { _, proxyMethod, args ->
                    when (proxyMethod.name) {
                        "onResult" -> {
                            val result = args?.get(0)
                            Log.i(TAG, "  Satellite supported (API): $result")
                            if (result == true || result == java.lang.Boolean.TRUE) {
                                satelliteManagerSupported = true
                                updateModemStateInUI()
                            }
                        }
                        "onError" -> {
                            val error = args?.get(0)
                            Log.w(TAG, "  requestIsSupported error: $error")
                        }
                    }
                    null
                }
                method.invoke(manager, executor, receiver)
            } else {
                Log.d(TAG, "  requestIsSupported method not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Error querying satellite supported: ${e.message}")
        }
    }

    private fun tryRegisterModemStateCallback(manager: Any, managerClass: Class<*>, executor: java.util.concurrent.Executor) {
        try {
            // Look for registerForModemStateChanged or registerForSatelliteModemStateChanged
            val method = managerClass.methods.find {
                it.name.contains("registerFor") && it.name.contains("ModemState")
            }
            if (method != null) {
                Log.i(TAG, "  Found modem state callback method: ${method.name}")
                // TODO: Implement callback registration when we understand the callback interface
            } else {
                Log.d(TAG, "  No modem state callback method found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Error registering modem state callback: ${e.message}")
        }
    }

    private fun tryQuerySatelliteCapabilities(manager: Any, managerClass: Class<*>, executor: java.util.concurrent.Executor) {
        try {
            val method = managerClass.methods.find {
                it.name == "requestCapabilities" || it.name == "requestSatelliteCapabilities"
            }
            if (method != null) {
                Log.i(TAG, "  Calling ${method.name}...")
                val outcomeReceiverClass = Class.forName("android.os.OutcomeReceiver")
                val receiver = java.lang.reflect.Proxy.newProxyInstance(
                    outcomeReceiverClass.classLoader,
                    arrayOf(outcomeReceiverClass)
                ) { _, proxyMethod, args ->
                    when (proxyMethod.name) {
                        "onResult" -> {
                            val result = args?.get(0)
                            Log.i(TAG, "  Satellite capabilities: $result")
                            if (result != null) {
                                parseSatelliteCapabilities(result)
                            }
                        }
                        "onError" -> {
                            val error = args?.get(0)
                            Log.w(TAG, "  requestCapabilities error: $error")
                        }
                    }
                    null
                }
                method.invoke(manager, executor, receiver)
            } else {
                Log.d(TAG, "  requestCapabilities method not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Error querying satellite capabilities: ${e.message}")
        }
    }

    private fun parseSatelliteCapabilities(capabilitiesObj: Any) {
        try {
            val capClass = capabilitiesObj.javaClass
            Log.i(TAG, "  Parsing capabilities from ${capClass.simpleName}")

            // Try to extract capability information using reflection
            capClass.methods.forEach { method ->
                if (method.parameterCount == 0 && (method.name.startsWith("is") || method.name.startsWith("get") || method.name.startsWith("supports"))) {
                    try {
                        val value = method.invoke(capabilitiesObj)
                        Log.i(TAG, "    ${method.name}: $value")
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Error parsing capabilities: ${e.message}")
        }
    }

    private fun updateModemStateInUI() {
        // Determine modem state based on available information
        val modemState = when {
            satelliteEnabled == true -> SatelliteModemState.IDLE
            satelliteEnabled == false -> SatelliteModemState.DISABLED
            satelliteProvisioned == false -> SatelliteModemState.NOT_CONNECTED
            else -> SatelliteModemState.UNKNOWN
        }

        currentModemState = modemState

        _satelliteState.update { state ->
            state.copy(
                modemState = modemState,
                satelliteManagerSupported = satelliteManagerSupported
            )
        }

        Log.i(TAG, "  Updated modem state: $modemState (enabled=$satelliteEnabled, provisioned=$satelliteProvisioned)")
    }
    
    /**
     * Get current satellite status summary
     */
    fun getSatelliteStatusSummary(): SatelliteStatusSummary {
        val state = _satelliteState.value

        return SatelliteStatusSummary(
            isConnected = state.isConnected,
            connectionType = state.connectionType,
            provider = state.provider,
            networkName = state.networkName,
            signalStrength = state.signalStrength,
            capabilities = state.capabilities,
            recentAnomalyCount = connectionHistory.count {
                System.currentTimeMillis() - it.timestamp < 3600000 && !it.wasExpected
            },
            deviceSupported = isDeviceSatelliteCapable(),
            // New fields for enhanced NTN detection
            modemState = state.modemState,
            nrarfcn = state.nrarfcn,
            ntnBand = state.nrarfcn?.let { NTNBands.getNtnBandFromArfcn(it) },
            measuredRttMs = state.measuredRttMs,
            estimatedOrbit = state.estimatedOrbit,
            satelliteManagerSupported = satelliteManagerSupported
        )
    }

    /**
     * Check if device is satellite capable
     */
    private fun isDeviceSatelliteCapable(): Boolean {
        val model = Build.MODEL.uppercase()

        // Known satellite-capable devices
        val knownSatelliteDevices = listOf(
            "PIXEL 9", "PIXEL 10",  // Skylo NTN
            "IPHONE 14", "IPHONE 15", "IPHONE 16",  // Globalstar
            "GALAXY S24", "GALAXY S25"  // Various NTN
        )

        return knownSatelliteDevices.any { model.contains(it) } ||
               context.packageManager.hasSystemFeature("android.hardware.telephony.satellite")
    }

    data class SatelliteStatusSummary(
        val isConnected: Boolean,
        val connectionType: SatelliteConnectionType,
        val provider: SatelliteProvider,
        val networkName: String?,
        val signalStrength: Int?,
        val capabilities: SatelliteCapabilities,
        val recentAnomalyCount: Int,
        val deviceSupported: Boolean,
        // New fields for enhanced NTN detection
        val modemState: SatelliteModemState = SatelliteModemState.UNKNOWN,
        val nrarfcn: Int? = null,
        val ntnBand: String? = null,
        val measuredRttMs: Long? = null,
        val estimatedOrbit: OrbitType = OrbitType.UNKNOWN,
        val satelliteManagerSupported: Boolean = false
    )

    /**
     * Convert satellite anomaly to Detection for storage
     */
    fun anomalyToDetection(anomaly: SatelliteAnomaly): Detection {
        val detectionMethod = mapAnomalyToDetectionMethod(anomaly.type)

        val threatLevel = when (anomaly.severity) {
            AnomalySeverity.CRITICAL -> ThreatLevel.CRITICAL
            AnomalySeverity.HIGH -> ThreatLevel.HIGH
            AnomalySeverity.MEDIUM -> ThreatLevel.MEDIUM
            AnomalySeverity.LOW -> ThreatLevel.LOW
            AnomalySeverity.INFO -> ThreatLevel.INFO
        }

        val threatScore = when (anomaly.severity) {
            AnomalySeverity.CRITICAL -> 95
            AnomalySeverity.HIGH -> 80
            AnomalySeverity.MEDIUM -> 60
            AnomalySeverity.LOW -> 40
            AnomalySeverity.INFO -> 20
        }

        // Build description from technical details
        val detailsStr = anomaly.technicalDetails.entries.joinToString(", ") { "${it.key}: ${it.value}" }

        return Detection(
            deviceType = DeviceType.SATELLITE_NTN,
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = detectionMethod,
            deviceName = "🛰️ ${formatAnomalyTypeName(anomaly.type)}",
            macAddress = null,
            ssid = _satelliteState.value.networkName,
            rssi = _satelliteState.value.signalStrength?.let { it * -20 - 40 } ?: -80, // Convert level to approximate dBm
            signalStrength = when (_satelliteState.value.signalStrength) {
                4 -> DetectionSignalStrength.EXCELLENT
                3 -> DetectionSignalStrength.GOOD
                2 -> DetectionSignalStrength.MEDIUM
                1 -> DetectionSignalStrength.WEAK
                else -> DetectionSignalStrength.VERY_WEAK
            },
            latitude = null, // Could add from location service
            longitude = null,
            threatLevel = threatLevel,
            threatScore = threatScore,
            manufacturer = formatProviderName(_satelliteState.value.provider),
            matchedPatterns = if (detailsStr.isNotEmpty()) detailsStr else anomaly.description
        )
    }

    private fun formatAnomalyTypeName(type: SatelliteAnomalyType): String {
        return type.name.split("_").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatProviderName(provider: SatelliteProvider): String {
        return when (provider) {
            SatelliteProvider.STARLINK -> "SpaceX Starlink"
            SatelliteProvider.SKYLO -> "Skylo NTN"
            SatelliteProvider.GLOBALSTAR -> "Globalstar"
            SatelliteProvider.AST_SPACEMOBILE -> "AST SpaceMobile"
            SatelliteProvider.LYNK -> "Lynk"
            SatelliteProvider.IRIDIUM -> "Iridium"
            SatelliteProvider.INMARSAT -> "Inmarsat"
            SatelliteProvider.UNKNOWN -> "Unknown Provider"
        }
    }

    /**
     * Map anomaly type to detection method - handles all anomaly types
     */
    private fun mapAnomalyToDetectionMethod(type: SatelliteAnomalyType): DetectionMethod {
        return when (type) {
            // Original anomalies
            SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.NTN_BAND_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.EPHEMERIS_MISMATCH -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> DetectionMethod.SAT_DOWNGRADE
            SatelliteAnomalyType.RTT_ORBIT_MISMATCH -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.UNEXPECTED_MODEM_STATE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.CAPABILITY_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.NRARFCN_NTN_BAND_INVALID -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Timing & Latency
            SatelliteAnomalyType.DOPPLER_SHIFT_MISMATCH -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.HANDOVER_TIMING_IMPOSSIBLE -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.PROPAGATION_DELAY_VARIANCE_WRONG -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.HARQ_RETRANSMISSION_TIMING_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.TIMING_ADVANCE_TOO_SMALL -> DetectionMethod.SAT_TIMING_ANOMALY

            // Orbital & Ephemeris
            SatelliteAnomalyType.SATELLITE_BELOW_HORIZON -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.WRONG_ORBITAL_PLANE -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.PASS_DURATION_EXCEEDED -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.ELEVATION_ANGLE_IMPOSSIBLE -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.TLE_POSITION_MISMATCH -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.SATELLITE_ID_REUSE -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Signal & RF
            SatelliteAnomalyType.SIGNAL_TOO_STRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.WRONG_POLARIZATION -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.BANDWIDTH_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.CARRIER_FREQUENCY_DRIFT_WRONG -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.MULTIPATH_IN_CLEAR_SKY -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.SUBCARRIER_SPACING_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Protocol & Network
            SatelliteAnomalyType.MIB_SIB_INCONSISTENT -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.PLMN_NOT_NTN_REGISTERED -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.CELL_ID_FORMAT_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.PAGING_CYCLE_TERRESTRIAL -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.DRX_TOO_SHORT -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.RACH_PROCEDURE_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.MEASUREMENT_GAP_MISSING -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.GNSS_ASSISTANCE_REJECTED -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Security (all map to SAT_SUSPICIOUS_NTN with high severity)
            SatelliteAnomalyType.ENCRYPTION_DOWNGRADE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.IDENTITY_REQUEST_FLOOD -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.REPLAY_ATTACK_DETECTED -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.CERTIFICATE_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.NULL_CIPHER_OFFERED -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.AUTH_REJECT_LOOP -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.SUPI_CONCEALMENT_DISABLED -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Coverage & Location
            SatelliteAnomalyType.NTN_IN_FULL_TERRESTRIAL_COVERAGE -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.COVERAGE_HOLE_IMPOSSIBLE -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.GEOFENCE_VIOLATION -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.INDOOR_SATELLITE_CONNECTION -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.ALTITUDE_INCOMPATIBLE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.URBAN_CANYON_SATELLITE -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Multi-System Correlation
            SatelliteAnomalyType.GNSS_NTN_TIME_CONFLICT -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.GNSS_POSITION_COVERAGE_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.SIMULTANEOUS_GNSS_JAMMING -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.CELLULAR_NTN_GEOMETRY_IMPOSSIBLE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.WIFI_SATELLITE_CONFLICT -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Behavioral
            SatelliteAnomalyType.NTN_TRACKING_PATTERN -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.FORCED_NTN_AFTER_CALL -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.SELECTIVE_NTN_ROUTING -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.NTN_CAMPING_PERSISTENT -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.HANDOVER_BACK_BLOCKED -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.PEER_DEVICE_DIVERGENCE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.TIME_OF_DAY_VISIBILITY_ANOMALY -> DetectionMethod.SAT_TIMING_ANOMALY

            // Hardware/Modem
            SatelliteAnomalyType.MODEM_STATE_TRANSITION_IMPOSSIBLE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.CAPABILITY_ANNOUNCEMENT_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.BASEBAND_FIRMWARE_TAMPERED -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.ANTENNA_CONFIGURATION_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.POWER_CLASS_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.SIMULTANEOUS_BAND_CONFLICT -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Provider-Specific
            SatelliteAnomalyType.STARLINK_ORBITAL_PARAMS_WRONG -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.SKYLO_MODEM_MISSING -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.IRIDIUM_CONSTELLATION_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.GLOBALSTAR_BAND_WRONG -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.AST_SPACEMOBILE_PREMATURE -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.PROVIDER_CAPABILITY_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Message/Data
            SatelliteAnomalyType.SMS_ROUTING_SUSPICIOUS -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.DATAGRAM_SIZE_EXCEEDED -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.MESSAGE_LATENCY_WRONG -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.ACK_TIMING_TERRESTRIAL -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.STORE_FORWARD_MISSING -> DetectionMethod.SAT_SUSPICIOUS_NTN

            // Emergency
            SatelliteAnomalyType.SOS_REDIRECT_SUSPICIOUS -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.E911_LOCATION_INJECTION -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.EMERGENCY_CALL_BLOCKED -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.FAKE_EMERGENCY_ALERT -> DetectionMethod.SAT_SUSPICIOUS_NTN
        }
    }

    // ========================================================================
    // Comprehensive Anomaly Detection Engine
    // ========================================================================

    /**
     * Run all anomaly detection checks against the current context
     */
    suspend fun runComprehensiveDetection(context: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()

        // Run all detection categories
        anomalies.addAll(detectTimingAnomalies(context))
        anomalies.addAll(detectOrbitalAnomalies(context))
        anomalies.addAll(detectSignalAnomalies(context))
        anomalies.addAll(detectProtocolAnomalies(context))
        anomalies.addAll(detectSecurityAnomalies(context))
        anomalies.addAll(detectCoverageAnomalies(context))
        anomalies.addAll(detectCorrelationAnomalies(context))
        anomalies.addAll(detectBehavioralAnomalies(context))
        anomalies.addAll(detectHardwareAnomalies(context))
        anomalies.addAll(detectProviderAnomalies(context))
        anomalies.addAll(detectMessageAnomalies(context))
        anomalies.addAll(detectEmergencyAnomalies(context))

        // Emit all detected anomalies
        for (anomaly in anomalies) {
            _anomalies.emit(anomaly)
        }

        return anomalies
    }

    // ---------- Timing & Latency Detection ----------

    private fun detectTimingAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val signal = ctx.signalCharacteristics ?: return anomalies
        val state = ctx.connectionState

        // TIMING_ADVANCE_TOO_SMALL: TA suggests ground-based not satellite
        signal.estimatedDistance?.let { distKm ->
            if (distKm < DetectionThresholds.TIMING_ADVANCE_MIN_SATELLITE_KM && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.TIMING_ADVANCE_TOO_SMALL,
                    AnomalySeverity.CRITICAL,
                    "Timing advance indicates ${distKm.toInt()}km - too close for satellite",
                    mapOf("estimated_distance_km" to distKm, "min_expected_km" to DetectionThresholds.TIMING_ADVANCE_MIN_SATELLITE_KM)
                ))
            }
        }

        // DOPPLER_SHIFT_MISMATCH: LEO should have significant Doppler
        if (getExpectedOrbit(state.provider) == OrbitType.LEO) {
            signal.dopplerMeasured?.let { measured ->
                signal.dopplerExpected?.let { expected ->
                    val tolerance = expected * DetectionThresholds.DOPPLER_TOLERANCE_PERCENT / 100
                    if (kotlin.math.abs(measured - expected) > tolerance) {
                        anomalies.add(createAnomaly(
                            SatelliteAnomalyType.DOPPLER_SHIFT_MISMATCH,
                            AnomalySeverity.HIGH,
                            "Measured Doppler ${measured.toInt()}Hz doesn't match expected ${expected.toInt()}Hz for LEO",
                            mapOf("measured_hz" to measured, "expected_hz" to expected, "tolerance_percent" to DetectionThresholds.DOPPLER_TOLERANCE_PERCENT)
                        ))
                    }
                }
            }
            // Also check if LEO but no Doppler detected
            if (signal.dopplerMeasured == null || signal.dopplerMeasured < DetectionThresholds.LEO_DOPPLER_SHIFT_MIN_HZ) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.DOPPLER_SHIFT_MISMATCH,
                    AnomalySeverity.HIGH,
                    "LEO satellite claimed but Doppler shift too low or missing",
                    mapOf("measured_hz" to (signal.dopplerMeasured ?: 0), "min_expected_hz" to DetectionThresholds.LEO_DOPPLER_SHIFT_MIN_HZ)
                ))
            }
        }

        // HARQ_RETRANSMISSION_TIMING_WRONG: NTN uses extended HARQ
        ctx.protocolParameters?.harqRttMs?.let { harqRtt ->
            if (harqRtt < DetectionThresholds.MAX_TERRESTRIAL_HARQ_RTT_MS && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.HARQ_RETRANSMISSION_TIMING_WRONG,
                    AnomalySeverity.MEDIUM,
                    "HARQ RTT ${harqRtt}ms is terrestrial-like, not NTN-extended",
                    mapOf("harq_rtt_ms" to harqRtt, "max_terrestrial_ms" to DetectionThresholds.MAX_TERRESTRIAL_HARQ_RTT_MS)
                ))
            }
        }

        return anomalies
    }

    // ---------- Orbital & Ephemeris Detection ----------

    private fun detectOrbitalAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val state = ctx.connectionState
        val location = ctx.locationContext

        // SATELLITE_BELOW_HORIZON: Check satellite elevation
        ctx.calculatedSatellitePosition?.let { satPos ->
            if (!satPos.isVisible || satPos.elevation < 0) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.SATELLITE_BELOW_HORIZON,
                    AnomalySeverity.CRITICAL,
                    "Claimed satellite ${satPos.satelliteId} is below horizon (elevation: ${satPos.elevation.toInt()}°)",
                    mapOf("satellite_id" to satPos.satelliteId, "elevation" to satPos.elevation, "azimuth" to satPos.azimuth)
                ))
            }
        }

        // PASS_DURATION_EXCEEDED: LEO pass can't last forever
        ctx.behavioralContext?.currentConnectionDuration?.let { duration ->
            val provider = state.provider
            val maxDurationFromProvider = ProviderOrbitalData.getForProvider(provider)?.maxPassDurationMin?.let { (it * 60 * 1000).toLong() }
            val maxDuration: Long = maxDurationFromProvider ?: DetectionThresholds.MAX_LEO_PASS_DURATION_MS

            if (duration > maxDuration && getExpectedOrbit(provider) == OrbitType.LEO) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.PASS_DURATION_EXCEEDED,
                    AnomalySeverity.HIGH,
                    "Connection duration ${duration / 60000}min exceeds max LEO pass time",
                    mapOf("duration_ms" to duration, "max_pass_ms" to maxDuration, "provider" to provider.name)
                ))
            }
        }

        // TLE_POSITION_MISMATCH: Compare claimed vs calculated position
        if (ctx.claimedSatellitePosition != null && ctx.calculatedSatellitePosition != null) {
            val claimed = ctx.claimedSatellitePosition
            val calculated = ctx.calculatedSatellitePosition

            val positionDiff = calculateDistance(
                claimed.latitude, claimed.longitude,
                calculated.latitude, calculated.longitude
            )

            if (positionDiff > 500) { // More than 500km difference
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.TLE_POSITION_MISMATCH,
                    AnomalySeverity.HIGH,
                    "Claimed satellite position differs from TLE-calculated by ${positionDiff.toInt()}km",
                    mapOf(
                        "claimed_lat" to claimed.latitude, "claimed_lon" to claimed.longitude,
                        "calculated_lat" to calculated.latitude, "calculated_lon" to calculated.longitude,
                        "difference_km" to positionDiff
                    )
                ))
            }
        }

        // STARLINK_ORBITAL_PARAMS_WRONG: Provider-specific orbital validation
        ProviderOrbitalData.getForProvider(state.provider)?.let { params ->
            ctx.calculatedSatellitePosition?.let { pos ->
                if (pos.altitude !in params.altitudeKm.first..params.altitudeKm.second) {
                    anomalies.add(createAnomaly(
                        SatelliteAnomalyType.STARLINK_ORBITAL_PARAMS_WRONG,
                        AnomalySeverity.HIGH,
                        "${state.provider.name} altitude ${pos.altitude.toInt()}km outside expected ${params.altitudeKm}",
                        mapOf("altitude_km" to pos.altitude, "expected_range" to params.altitudeKm)
                    ))
                }
            }
        }

        return anomalies
    }

    // ---------- Signal & RF Detection ----------

    private fun detectSignalAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val signal = ctx.signalCharacteristics ?: return anomalies
        val state = ctx.connectionState

        // SIGNAL_TOO_STRONG: Satellite signals should be weak
        signal.rsrp?.let { rsrp ->
            if (rsrp > DetectionThresholds.NTN_SIGNAL_MAX_DBM && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.SIGNAL_TOO_STRONG,
                    AnomalySeverity.HIGH,
                    "Signal strength ${rsrp}dBm too strong for satellite (max expected: ${DetectionThresholds.NTN_SIGNAL_MAX_DBM}dBm)",
                    mapOf("rsrp_dbm" to rsrp, "max_expected_dbm" to DetectionThresholds.NTN_SIGNAL_MAX_DBM)
                ))
            }
        }

        // WRONG_POLARIZATION: Satellites use circular polarization
        signal.polarization?.let { pol ->
            if (pol == Polarization.LINEAR_HORIZONTAL || pol == Polarization.LINEAR_VERTICAL) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.WRONG_POLARIZATION,
                    AnomalySeverity.MEDIUM,
                    "Linear polarization detected - satellites use circular polarization",
                    mapOf("detected_polarization" to pol.name)
                ))
            }
        }

        // BANDWIDTH_MISMATCH: NTN channels are narrow
        signal.channelBandwidth?.let { bw ->
            if (bw > DetectionThresholds.MAX_NTN_BANDWIDTH_MHZ) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.BANDWIDTH_MISMATCH,
                    AnomalySeverity.MEDIUM,
                    "Channel bandwidth ${bw}MHz exceeds NTN limit of ${DetectionThresholds.MAX_NTN_BANDWIDTH_MHZ}MHz",
                    mapOf("bandwidth_mhz" to bw, "max_ntn_mhz" to DetectionThresholds.MAX_NTN_BANDWIDTH_MHZ)
                ))
            }
        }

        // SUBCARRIER_SPACING_WRONG: NTN uses 15 or 30 kHz SCS
        signal.subcarrierSpacing?.let { scs ->
            if (scs !in DetectionThresholds.VALID_SCS_NTN) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.SUBCARRIER_SPACING_WRONG,
                    AnomalySeverity.HIGH,
                    "Subcarrier spacing ${scs}kHz invalid for NTN (must be 15 or 30)",
                    mapOf("scs_khz" to scs, "valid_values" to DetectionThresholds.VALID_SCS_NTN)
                ))
            }
        }

        // MULTIPATH_IN_CLEAR_SKY: Satellite should be LOS
        ctx.locationContext?.let { loc ->
            if ((loc.skyViewFactor ?: 1.0) > 0.8 && (signal.multipathIndicator ?: 0.0) > 0.5) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.MULTIPATH_IN_CLEAR_SKY,
                    AnomalySeverity.MEDIUM,
                    "Significant multipath detected despite clear sky view",
                    mapOf("sky_view" to (loc.skyViewFactor ?: 0), "multipath" to (signal.multipathIndicator ?: 0))
                ))
            }
        }

        return anomalies
    }

    // ---------- Protocol & Network Detection ----------

    private fun detectProtocolAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val protocol = ctx.protocolParameters ?: return anomalies
        val state = ctx.connectionState

        // PLMN_NOT_NTN_REGISTERED: Check if PLMN is known NTN
        protocol.plmn?.let { plmn ->
            if (plmn.isNotEmpty() && plmn !in KNOWN_NTN_PLMNS && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.PLMN_NOT_NTN_REGISTERED,
                    AnomalySeverity.MEDIUM,
                    "PLMN $plmn not in known NTN operator list",
                    mapOf("plmn" to plmn, "known_plmns" to KNOWN_NTN_PLMNS.take(5))
                ))
            }
        }

        // DRX_TOO_SHORT: NTN uses extended DRX
        protocol.drxCycle?.let { drx ->
            if (drx < DetectionThresholds.MIN_NTN_DRX_CYCLE_MS && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.DRX_TOO_SHORT,
                    AnomalySeverity.LOW,
                    "DRX cycle ${drx}ms too short for NTN (min: ${DetectionThresholds.MIN_NTN_DRX_CYCLE_MS}ms)",
                    mapOf("drx_ms" to drx, "min_ntn_ms" to DetectionThresholds.MIN_NTN_DRX_CYCLE_MS)
                ))
            }
        }

        // PAGING_CYCLE_TERRESTRIAL: NTN uses extended paging
        protocol.pagingCycle?.let { paging ->
            if (paging < DetectionThresholds.MIN_NTN_PAGING_CYCLE_MS && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.PAGING_CYCLE_TERRESTRIAL,
                    AnomalySeverity.LOW,
                    "Paging cycle ${paging}ms is terrestrial-like",
                    mapOf("paging_ms" to paging, "min_ntn_ms" to DetectionThresholds.MIN_NTN_PAGING_CYCLE_MS)
                ))
            }
        }

        // MEASUREMENT_GAP_MISSING: NTN needs measurement gaps
        if (protocol.measurementGapConfig == false && state.isConnected) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.MEASUREMENT_GAP_MISSING,
                AnomalySeverity.MEDIUM,
                "Measurement gaps not configured - required for NTN Doppler/delay tracking",
                mapOf("measurement_gaps" to false)
            ))
        }

        return anomalies
    }

    // ---------- Security Detection ----------

    private fun detectSecurityAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val protocol = ctx.protocolParameters ?: return anomalies
        val state = ctx.connectionState

        // NULL_CIPHER_OFFERED: Never acceptable
        if (protocol.encryptionAlgorithm == "NEA0" && state.isConnected) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.NULL_CIPHER_OFFERED,
                AnomalySeverity.CRITICAL,
                "Null cipher (NEA0) offered on satellite connection",
                mapOf("encryption" to "NEA0")
            ))
        }

        // ENCRYPTION_DOWNGRADE: Check for weak encryption
        val weakAlgorithms = setOf("NEA0", "NEA1")
        protocol.encryptionAlgorithm?.let { encryption ->
            if (encryption in weakAlgorithms && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.ENCRYPTION_DOWNGRADE,
                    AnomalySeverity.HIGH,
                    "Weak encryption $encryption on satellite connection",
                    mapOf("encryption" to encryption)
                ))
            }
        }

        return anomalies
    }

    // ---------- Coverage & Location Detection ----------

    private fun detectCoverageAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val location = ctx.locationContext ?: return anomalies
        val state = ctx.connectionState

        // INDOOR_SATELLITE_CONNECTION: Can't get satellite indoors
        if (location.isIndoors == true && state.isConnected) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.INDOOR_SATELLITE_CONNECTION,
                AnomalySeverity.HIGH,
                "Satellite connection established while indoors - no sky view possible",
                mapOf("is_indoors" to true)
            ))
        }

        // URBAN_CANYON_SATELLITE: Limited sky view in dense urban
        if (location.urbanDensity == UrbanDensity.DENSE_URBAN &&
            (location.skyViewFactor ?: 0.0) < DetectionThresholds.MIN_SKY_VIEW_FACTOR &&
            state.isConnected) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.URBAN_CANYON_SATELLITE,
                AnomalySeverity.MEDIUM,
                "Satellite connection in dense urban with limited sky view (${((location.skyViewFactor ?: 0.0) * 100).toInt()}%)",
                mapOf("urban_density" to location.urbanDensity.name, "sky_view" to (location.skyViewFactor ?: 0))
            ))
        }

        // NTN_IN_FULL_TERRESTRIAL_COVERAGE: Why satellite if good terrestrial?
        location.terrestrialSignalDbm?.let { terrestrial ->
            if (terrestrial > DetectionThresholds.EXCELLENT_TERRESTRIAL_DBM && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.NTN_IN_FULL_TERRESTRIAL_COVERAGE,
                    AnomalySeverity.MEDIUM,
                    "On satellite despite excellent terrestrial coverage (${terrestrial}dBm)",
                    mapOf("terrestrial_dbm" to terrestrial, "threshold_dbm" to DetectionThresholds.EXCELLENT_TERRESTRIAL_DBM)
                ))
            }
        }

        return anomalies
    }

    // ---------- Multi-System Correlation Detection ----------

    private fun detectCorrelationAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val location = ctx.locationContext ?: return anomalies
        val state = ctx.connectionState

        // GNSS_NTN_TIME_CONFLICT: GNSS and NTN time should match
        location.timeDiscrepancyMs?.let { diff ->
            if (kotlin.math.abs(diff) > DetectionThresholds.MAX_GNSS_NTN_TIME_DIFF_MS) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.GNSS_NTN_TIME_CONFLICT,
                    AnomalySeverity.HIGH,
                    "GNSS and NTN time differ by ${diff}ms (max: ${DetectionThresholds.MAX_GNSS_NTN_TIME_DIFF_MS}ms)",
                    mapOf("time_diff_ms" to diff, "max_diff_ms" to DetectionThresholds.MAX_GNSS_NTN_TIME_DIFF_MS)
                ))
            }
        }

        // SIMULTANEOUS_GNSS_JAMMING: Correlated attack indicator
        if (location.gnssFixAvailable == false && state.isConnected) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.SIMULTANEOUS_GNSS_JAMMING,
                AnomalySeverity.CRITICAL,
                "GNSS unavailable during satellite connection - possible coordinated attack",
                mapOf("gnss_available" to false, "ntn_connected" to true)
            ))
        }

        return anomalies
    }

    // ---------- Behavioral Detection ----------

    private fun detectBehavioralAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val behavior = ctx.behavioralContext ?: return anomalies
        val state = ctx.connectionState

        // PEER_DEVICE_DIVERGENCE: Your device on NTN, others on terrestrial
        behavior.peerDeviceStates?.let { peers ->
            val peersOnTerrestrial = peers.count { !it.isOnNtn }
            val peersOnNtn = peers.count { it.isOnNtn }

            if (state.isConnected && peersOnTerrestrial > 0 && peersOnNtn == 0) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.PEER_DEVICE_DIVERGENCE,
                    AnomalySeverity.HIGH,
                    "Your device on satellite while $peersOnTerrestrial nearby devices on terrestrial - possible targeting",
                    mapOf("peers_terrestrial" to peersOnTerrestrial, "peers_ntn" to peersOnNtn)
                ))
            }
        }

        // HANDOVER_BACK_BLOCKED: Network preventing return to terrestrial
        if ((behavior.handoverBlockedCount ?: 0) > 2) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.HANDOVER_BACK_BLOCKED,
                AnomalySeverity.HIGH,
                "Handover to terrestrial blocked ${behavior.handoverBlockedCount} times",
                mapOf("blocked_count" to (behavior.handoverBlockedCount ?: 0))
            ))
        }

        // FORCED_NTN_AFTER_CALL: NTN immediately after call
        val recentCall = behavior.recentCalls.maxByOrNull { it.timestamp }
        if (recentCall != null && state.isConnected) {
            val timeSinceCall = ctx.timestamp - recentCall.timestamp
            if (timeSinceCall < DetectionThresholds.SUSPICIOUS_NTN_AFTER_CALL_WINDOW_MS) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.FORCED_NTN_AFTER_CALL,
                    AnomalySeverity.HIGH,
                    "Forced to satellite ${timeSinceCall / 1000}s after call - possible interception setup",
                    mapOf("time_since_call_ms" to timeSinceCall)
                ))
            }
        }

        return anomalies
    }

    // ---------- Hardware Detection ----------

    private fun detectHardwareAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val hardware = ctx.hardwareContext ?: return anomalies
        val state = ctx.connectionState

        // MODEM_STATE_TRANSITION_IMPOSSIBLE: Invalid state transition
        val lastTransition = hardware.modemStateTransitions.lastOrNull()
        if (lastTransition != null && !lastTransition.wasValid) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.MODEM_STATE_TRANSITION_IMPOSSIBLE,
                AnomalySeverity.MEDIUM,
                "Invalid modem transition: ${lastTransition.fromState} -> ${lastTransition.toState}",
                mapOf("from" to lastTransition.fromState.name, "to" to lastTransition.toState.name)
            ))
        }

        // SKYLO_MODEM_MISSING: Skylo requires specific modem
        if (state.provider == SatelliteProvider.SKYLO) {
            val hasRequiredModem = hardware.basebandVersion?.let { version ->
                SKYLO_CAPABLE_MODEMS.any { version.contains(it, ignoreCase = true) }
            } ?: false

            if (!hasRequiredModem && state.isConnected) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.SKYLO_MODEM_MISSING,
                    AnomalySeverity.HIGH,
                    "Skylo NTN claimed but device lacks required modem (need: ${SKYLO_CAPABLE_MODEMS.joinToString()})",
                    mapOf("baseband" to (hardware.basebandVersion ?: "unknown"), "required" to SKYLO_CAPABLE_MODEMS)
                ))
            }
        }

        return anomalies
    }

    // ---------- Provider-Specific Detection ----------

    private fun detectProviderAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val state = ctx.connectionState

        // AST_SPACEMOBILE_PREMATURE: Not commercially launched yet
        if (state.provider == SatelliteProvider.AST_SPACEMOBILE &&
            System.currentTimeMillis() < ProviderLaunchDates.AST_SPACEMOBILE) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.AST_SPACEMOBILE_PREMATURE,
                AnomalySeverity.CRITICAL,
                "AST SpaceMobile connection claimed before commercial launch",
                mapOf("current_time" to System.currentTimeMillis(), "launch_date" to ProviderLaunchDates.AST_SPACEMOBILE)
            ))
        }

        // GLOBALSTAR_BAND_WRONG: Globalstar uses specific S-band
        if (state.provider == SatelliteProvider.GLOBALSTAR && state.frequency != null) {
            val freq = state.frequency
            if (freq !in 1610..1619) { // Globalstar S-band
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.GLOBALSTAR_BAND_WRONG,
                    AnomalySeverity.HIGH,
                    "Globalstar claimed but frequency ${freq}MHz not in S-band (1610-1619MHz)",
                    mapOf("frequency_mhz" to freq, "expected_range" to "1610-1619")
                ))
            }
        }

        return anomalies
    }

    // ---------- Message/Data Detection ----------

    private fun detectMessageAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val behavior = ctx.behavioralContext ?: return anomalies
        val state = ctx.connectionState

        // MESSAGE_LATENCY_WRONG: Check SMS latency against expected satellite latency
        val recentSms = behavior.recentSms.filter { it.wasOnNtn }
        val firstInvalidSms = recentSms.firstOrNull { sms ->
            sms.deliveryLatencyMs?.let { latency ->
                val expectedMin = ProviderOrbitalData.getForProvider(state.provider)?.expectedLatencyMs?.first ?: 20
                latency < expectedMin
            } ?: false
        }
        firstInvalidSms?.deliveryLatencyMs?.let { latency ->
            val expectedMin = ProviderOrbitalData.getForProvider(state.provider)?.expectedLatencyMs?.first ?: 20
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.MESSAGE_LATENCY_WRONG,
                AnomalySeverity.MEDIUM,
                "SMS delivery latency ${latency}ms too fast for satellite (min: ${expectedMin}ms)",
                mapOf("latency_ms" to latency, "min_expected_ms" to expectedMin)
            ))
        }

        return anomalies
    }

    // ---------- Emergency Services Detection ----------

    private fun detectEmergencyAnomalies(ctx: ComprehensiveDetectionContext): List<SatelliteAnomaly> {
        val anomalies = mutableListOf<SatelliteAnomaly>()
        val emergency = ctx.emergencyContext ?: return anomalies

        // E911_LOCATION_INJECTION: Location mismatch in emergency call
        emergency.locationDiscrepancyMeters?.let { discrepancy ->
            if (discrepancy > 1000) { // More than 1km off
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.E911_LOCATION_INJECTION,
                    AnomalySeverity.CRITICAL,
                    "Emergency call location differs from GPS by ${(discrepancy / 1000).toInt()}km - possible injection",
                    mapOf("discrepancy_meters" to discrepancy)
                ))
            }
        }

        // EMERGENCY_CALL_BLOCKED: Critical safety issue
        if (emergency.isEmergencyBlocked == true) {
            anomalies.add(createAnomaly(
                SatelliteAnomalyType.EMERGENCY_CALL_BLOCKED,
                AnomalySeverity.CRITICAL,
                "Emergency call blocked while claiming satellite connection",
                mapOf("blocked" to true)
            ))
        }

        // FAKE_EMERGENCY_ALERT: Unverified WEA/CMAS
        for (alert in emergency.recentAlerts) {
            if (!alert.isVerified) {
                anomalies.add(createAnomaly(
                    SatelliteAnomalyType.FAKE_EMERGENCY_ALERT,
                    AnomalySeverity.HIGH,
                    "Unverified emergency alert (${alert.type}) from satellite source",
                    mapOf("alert_type" to alert.type, "source" to alert.source)
                ))
            }
        }

        return anomalies
    }

    // ---------- Helper Methods ----------

    private fun createAnomaly(
        type: SatelliteAnomalyType,
        severity: AnomalySeverity,
        description: String,
        details: Map<String, Any>
    ): SatelliteAnomaly {
        return SatelliteAnomaly(
            type = type,
            severity = severity,
            description = description,
            technicalDetails = details,
            recommendations = getRecommendationsForAnomaly(type)
        )
    }

    private fun getRecommendationsForAnomaly(type: SatelliteAnomalyType): List<String> {
        return when (type) {
            // Critical security anomalies
            SatelliteAnomalyType.NULL_CIPHER_OFFERED,
            SatelliteAnomalyType.ENCRYPTION_DOWNGRADE,
            SatelliteAnomalyType.IDENTITY_REQUEST_FLOOD -> listOf(
                "Immediately disable cellular/satellite connection",
                "Enable airplane mode",
                "Move to a different location",
                "Report to carrier"
            )

            // Timing/orbital anomalies suggesting spoofing
            SatelliteAnomalyType.TIMING_ADVANCE_TOO_SMALL,
            SatelliteAnomalyType.SATELLITE_BELOW_HORIZON,
            SatelliteAnomalyType.RTT_ORBIT_MISMATCH -> listOf(
                "This may indicate ground-based spoofing",
                "Verify with alternative connectivity",
                "Check device settings for forced satellite mode"
            )

            // Emergency anomalies
            SatelliteAnomalyType.EMERGENCY_CALL_BLOCKED,
            SatelliteAnomalyType.E911_LOCATION_INJECTION -> listOf(
                "CRITICAL: Find alternative means to contact emergency services",
                "Use WiFi calling if available",
                "Move to area with terrestrial coverage"
            )

            // Behavioral targeting
            SatelliteAnomalyType.PEER_DEVICE_DIVERGENCE,
            SatelliteAnomalyType.FORCED_NTN_AFTER_CALL -> listOf(
                "You may be specifically targeted",
                "Consider device security audit",
                "Use encrypted communications"
            )

            else -> listOf(
                "Monitor for additional anomalies",
                "Document occurrences with timestamps",
                "Consider disabling satellite if not needed"
            )
        }
    }

    /**
     * Calculate distance between two points in km (Haversine formula)
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    /**
     * Validate modem state transition
     */
    fun isValidModemTransition(from: SatelliteModemState, to: SatelliteModemState): Boolean {
        return VALID_MODEM_TRANSITIONS[from]?.contains(to) ?: false
    }
}

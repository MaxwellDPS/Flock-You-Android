package com.flockyou.monitoring

/**
 * Satellite Detection Heuristics Database
 * 
 * Comprehensive patterns and heuristics for detecting satellite connectivity
 * and potential surveillance via satellite network manipulation.
 * 
 * Based on:
 * - T-Mobile Starlink Direct to Cell (3GPP Release 17)
 * - Skylo NTN (Google Pixel 9/10)
 * - 3GPP TS 38.101-5, TS 36.102 NTN specifications
 * - Direct-to-Device (D2D) satellite research
 */
object SatelliteDetectionHeuristics {
    
    // ========================================================================
    // T-MOBILE STARLINK DIRECT TO CELL SPECIFICATIONS
    // ========================================================================
    
    /**
     * T-Mobile Starlink network identification patterns
     * These are the official network names displayed when connected via satellite
     */
    object TMobileStarlink {
        // Network names shown on device
        val NETWORK_NAMES = listOf(
            "T-Mobile SpaceX",      // Primary identifier
            "T-Sat+Starlink",       // Alternative identifier
            "T-Satellite",          // Service brand name
            "Starlink"              // Fallback display
        )
        
        // MCC/MNC codes (T-Mobile US)
        val MCC = "310"
        val MNC_LIST = listOf("260", "200", "210", "220", "230", "240", "250", "270", "310", "490", "660", "800")
        
        // Service specifications (as of January 2026)
        object ServiceSpecs {
            const val LAUNCH_DATE = "2025-07-23"
            const val SATELLITE_COUNT = 650
            const val ORBITAL_ALTITUDE_KM = 540  // LEO
            const val COVERAGE_AREA_SQ_MILES = 500000
            const val SPEED_ORBITAL_MPH = 17000
            
            // Supported features
            const val SUPPORTS_SMS = true
            const val SUPPORTS_MMS = true         // Added late 2025
            const val SUPPORTS_VOICE = false       // Coming 2026
            const val SUPPORTS_DATA_LIMITED = true // Select apps only
            const val SUPPORTS_EMERGENCY_911 = true
            const val SUPPORTS_LOCATION_SHARING = true
            
            // Supported apps for data
            val SUPPORTED_APPS = listOf(
                "WhatsApp",
                "Google Maps",
                "AllTrails",
                "AccuWeather",
                "X (Twitter)",
                "T-Life"
            )
            
            // Pricing
            const val T_MOBILE_CUSTOMER_PRICE = 0.0     // Free with Experience Beyond
            const val OTHER_CARRIER_PRICE = 10.0        // $10/month for AT&T/Verizon
        }
        
        // 3GPP Release 17 characteristics
        object TechnicalSpecs {
            const val STANDARD = "3GPP Release 17"
            const val ACCESS_TECHNOLOGY = "LTE"        // Uses T-Mobile LTE spectrum
            const val SPECTRUM_TYPE = "Mid-band"
            
            // Timing characteristics
            const val TYPICAL_LATENCY_MS = 30          // LEO typical
            const val MAX_LATENCY_MS = 100             // Under load
            const val MESSAGE_SEND_TIME_TYPICAL_S = 10 // Typical SMS send time
            const val MESSAGE_SEND_TIME_MAX_S = 60     // Max under poor conditions
            
            // Signal characteristics
            const val BEAMFORMING = true
            const val TDMA = true
            const val HARQ_PROCESSES = 32              // Increased for NTN
            val CHANNEL_BW_OPTIONS = listOf(5, 10, 15, 20, 30) // MHz
        }
        
        // Compatible devices (T-Mobile official list as of late 2025)
        val COMPATIBLE_DEVICES = listOf(
            // Apple
            "iPhone 14", "iPhone 14 Plus", "iPhone 14 Pro", "iPhone 14 Pro Max",
            "iPhone 15", "iPhone 15 Plus", "iPhone 15 Pro", "iPhone 15 Pro Max",
            "iPhone 16", "iPhone 16 Plus", "iPhone 16 Pro", "iPhone 16 Pro Max",
            
            // Google
            "Pixel 9", "Pixel 9 Pro", "Pixel 9 Pro XL", "Pixel 9 Pro Fold",
            "Pixel 10", "Pixel 10 Pro", "Pixel 10 Pro XL",
            
            // Samsung
            "Galaxy S23", "Galaxy S23+", "Galaxy S23 Ultra",
            "Galaxy S24", "Galaxy S24+", "Galaxy S24 Ultra",
            "Galaxy S25", "Galaxy S25+", "Galaxy S25 Ultra",
            "Galaxy Z Fold5", "Galaxy Z Fold6",
            "Galaxy Z Flip5", "Galaxy Z Flip6",
            
            // Motorola
            "moto g play 2026", "moto edge 2025", "moto g 5G 2025",
            "moto g 2024", "moto g power 5G 2025",
            "moto razr 2024", "moto razr+ 2024",
            "moto razr 2025", "moto razr+ 2025", "moto razr ultra 2025",
            "moto edge 2024", "moto edge 2022",
            "moto g stylus 2024"
        )
    }
    
    // ========================================================================
    // SKYLO NTN (GOOGLE PIXEL 9/10 SATELLITE SOS)
    // ========================================================================
    
    /**
     * Skylo NTN specifications for Pixel devices
     */
    object SkyloNTN {
        val NETWORK_NAMES = listOf(
            "Skylo",
            "Skylo NTN",
            "Satellite SOS",
            "Emergency Satellite"
        )
        
        // Supported regions (as of January 2026)
        val SUPPORTED_REGIONS = listOf(
            "United States (including Hawaii, Alaska, Puerto Rico)",
            "Canada",
            "France",
            "Germany",
            "Spain",
            "Switzerland",
            "United Kingdom",
            "Australia"
        )
        
        // Device support
        val SUPPORTED_DEVICES = listOf(
            // Pixel 9 series
            "Pixel 9",
            "Pixel 9 Pro",
            "Pixel 9 Pro XL",
            "Pixel 9 Pro Fold",
            // Pixel 10 series
            "Pixel 10",
            "Pixel 10 Pro", 
            "Pixel 10 Pro XL",
            // Pixel Watch
            "Pixel Watch 4"  // First smartwatch with NB-NTN
        )
        
        // Technical specifications
        object TechnicalSpecs {
            const val STANDARD = "3GPP Release 17 NB-IoT NTN"
            const val MODEM_PIXEL_9 = "Exynos 5400"
            const val MODEM_PIXEL_10 = "MediaTek T900"  // Changed from Samsung
            
            // Capabilities
            const val SUPPORTS_SOS = true
            const val SUPPORTS_LOCATION_SHARING = true  // New in Android 16
            const val SUPPORTS_SMS = true               // Carrier dependent
            
            // Emergency response partner
            const val EMERGENCY_PARTNER = "Garmin Response"
            
            // Service terms
            const val FREE_PERIOD_YEARS = 2  // Free for first 2 years
        }
        
        // Android API indicators
        object AndroidAPIs {
            const val SYSTEM_SERVICE = "SATELLITE_SERVICE"
            const val FEATURE_FLAG = "android.hardware.telephony.satellite"
            const val MANAGER_CLASS = "android.telephony.satellite.SatelliteManager"
            
            // Permission required
            const val PERMISSION = "android.permission.SATELLITE_COMMUNICATION"
        }
    }
    
    // ========================================================================
    // 3GPP NTN FREQUENCY BANDS
    // ========================================================================
    
    /**
     * Official 3GPP NTN frequency bands from TS 38.101-5 and TS 36.102
     */
    object NTNBands {
        // L-band bands
        data class BandDefinition(
            val bandNumber: String,
            val uplinkMHz: IntRange,
            val downlinkMHz: IntRange,
            val description: String,
            val release: String
        )
        
        val BANDS = listOf(
            BandDefinition("n253", 1626..1660, 1525..1559, "MSS L-band", "Rel-17"),
            BandDefinition("n254", 1626..1660, 1525..1559, "MSS L-band", "Rel-17"),
            BandDefinition("n255", 1626..1660, 1525..1559, "MSS L-band", "Rel-17"),
            BandDefinition("n256", 1980..2010, 2170..2200, "MSS S-band", "Rel-17"),
            // Higher bands for VSAT/ESIM (Rel-18+)
            BandDefinition("n510", 27500..29500, 17700..20200, "Ka-band", "Rel-18"),
            BandDefinition("n511", 27500..30000, 17700..21200, "Ka-band", "Rel-18"),
            BandDefinition("n512", 42500..43500, 0..0, "Ka-band (TDD)", "Rel-18")
        )
        
        // Check if frequency is in NTN range
        fun isNTNFrequency(freqMHz: Int): Boolean {
            return BANDS.any { band ->
                freqMHz in band.uplinkMHz || freqMHz in band.downlinkMHz
            }
        }
        
        // Get band name for frequency
        fun getBandForFrequency(freqMHz: Int): String? {
            return BANDS.find { band ->
                freqMHz in band.uplinkMHz || freqMHz in band.downlinkMHz
            }?.bandNumber
        }
    }
    
    // ========================================================================
    // SURVEILLANCE DETECTION HEURISTICS
    // ========================================================================
    
    /**
     * Heuristics for detecting potential surveillance via satellite manipulation
     */
    object SurveillanceHeuristics {
        
        /**
         * Heuristic 1: Unexpected Satellite Connection
         * 
         * Red flags:
         * - Connected to satellite when strong terrestrial signal exists
         * - No user-initiated satellite mode
         * - Occurs in urban/covered area
         */
        data class UnexpectedSatelliteIndicators(
            val hadRecentTerrestrialSignal: Boolean,
            val terrestrialSignalStrengthDbm: Int?,
            val timesSinceGoodTerrestrialMs: Long,
            val isUrbanArea: Boolean,
            val wasUserInitiated: Boolean
        ) {
            fun isSuspicious(): Boolean {
                return hadRecentTerrestrialSignal &&
                       (terrestrialSignalStrengthDbm ?: -120) > -100 &&
                       timesSinceGoodTerrestrialMs < 5000 &&
                       !wasUserInitiated
            }
            
            fun getSeverity(): String {
                return when {
                    isSuspicious() && isUrbanArea -> "HIGH"
                    isSuspicious() -> "MEDIUM"
                    else -> "LOW"
                }
            }
        }
        
        /**
         * Heuristic 2: Forced Network Downgrade
         * 
         * Surveillance devices may force downgrade from 5G/LTE to satellite
         * to intercept communications
         */
        data class ForcedDowngradeIndicators(
            val previousNetworkType: String,
            val currentNetworkType: String,
            val timeSinceDowngradeMs: Long,
            val signalBeforeDowngrade: Int?,
            val noUserAction: Boolean
        ) {
            fun isSuspicious(): Boolean {
                val wasBetterNetwork = previousNetworkType in listOf("5G", "LTE", "4G")
                val isNowSatellite = currentNetworkType.contains("Satellite", ignoreCase = true) ||
                                    currentNetworkType.contains("SAT", ignoreCase = true)
                return wasBetterNetwork && isNowSatellite && noUserAction &&
                       (signalBeforeDowngrade ?: -120) > -100
            }
        }
        
        /**
         * Heuristic 3: Unknown Satellite Network
         * 
         * Connection to unrecognized satellite network is highly suspicious
         */
        fun isKnownSatelliteNetwork(networkName: String): Boolean {
            val knownNetworks = TMobileStarlink.NETWORK_NAMES + 
                               SkyloNTN.NETWORK_NAMES +
                               listOf(
                                   "Globalstar", "AST SpaceMobile", "Lynk",
                                   "Iridium", "Inmarsat", "Thuraya"
                               )
            return knownNetworks.any { networkName.contains(it, ignoreCase = true) }
        }
        
        /**
         * Heuristic 4: NTN Timing Anomalies
         * 
         * LEO satellites have specific timing characteristics
         * Ground-based spoofing would have different timing
         */
        object TimingHeuristics {
            // Expected round-trip times
            const val LEO_MIN_RTT_MS = 20     // Minimum for LEO
            const val LEO_MAX_RTT_MS = 80     // Maximum for LEO
            const val MEO_MIN_RTT_MS = 80
            const val MEO_MAX_RTT_MS = 200
            const val GEO_MIN_RTT_MS = 200
            const val GEO_MAX_RTT_MS = 600
            
            // Ground-based would typically be <10ms
            const val SUSPICIOUS_RTT_MS = 10
            
            fun analyzeRTT(rttMs: Long, claimedOrbit: String): String {
                return when (claimedOrbit.uppercase()) {
                    "LEO" -> when {
                        rttMs < SUSPICIOUS_RTT_MS -> "SUSPICIOUS: RTT too low for LEO satellite"
                        rttMs in LEO_MIN_RTT_MS..LEO_MAX_RTT_MS -> "NORMAL: RTT consistent with LEO"
                        else -> "WARNING: RTT outside expected LEO range"
                    }
                    "MEO" -> when {
                        rttMs < LEO_MAX_RTT_MS -> "SUSPICIOUS: RTT too low for MEO"
                        rttMs in MEO_MIN_RTT_MS..MEO_MAX_RTT_MS -> "NORMAL"
                        else -> "WARNING: RTT outside expected MEO range"
                    }
                    "GEO" -> when {
                        rttMs < MEO_MAX_RTT_MS -> "SUSPICIOUS: RTT too low for GEO"
                        rttMs in GEO_MIN_RTT_MS..GEO_MAX_RTT_MS -> "NORMAL"
                        else -> "WARNING: RTT outside expected GEO range"
                    }
                    else -> "UNKNOWN orbit type"
                }
            }
        }
        
        /**
         * Heuristic 5: Rapid Satellite Switching
         * 
         * Legitimate satellite handoffs follow predictable patterns
         * Anomalous switching could indicate interference
         */
        object SwitchingHeuristics {
            // LEO satellites pass overhead in ~10-15 minutes
            const val MIN_HANDOFF_INTERVAL_MS = 60000L  // 1 minute minimum
            const val EXPECTED_HANDOFF_INTERVAL_MS = 600000L  // ~10 minutes typical
            
            // Max handoffs per hour (normal operations)
            const val MAX_HANDOFFS_PER_HOUR = 10
            
            fun analyzeSwitchingPattern(
                handoffTimestamps: List<Long>,
                windowMs: Long = 3600000
            ): String {
                val recentHandoffs = handoffTimestamps.filter { 
                    System.currentTimeMillis() - it < windowMs 
                }
                
                return when {
                    recentHandoffs.size > MAX_HANDOFFS_PER_HOUR * 2 -> 
                        "CRITICAL: Extremely rapid switching detected"
                    recentHandoffs.size > MAX_HANDOFFS_PER_HOUR ->
                        "WARNING: Unusually frequent satellite handoffs"
                    else -> "NORMAL: Switching pattern within expected range"
                }
            }
        }
        
        /**
         * Heuristic 6: Frequency Band Validation
         * 
         * Verify claimed satellite is using correct NTN bands
         */
        fun validateFrequencyForProvider(
            provider: String,
            frequencyMHz: Int
        ): Boolean {
            // All legitimate D2D satellite should be in NTN bands
            return NTNBands.isNTNFrequency(frequencyMHz)
        }
    }
    
    // ========================================================================
    // GLOBAL SATELLITE D2D CARRIERS
    // ========================================================================
    
    /**
     * Global carriers with Direct-to-Cell partnerships
     */
    object GlobalCarriers {
        data class CarrierPartnership(
            val carrier: String,
            val country: String,
            val satelliteProvider: String,
            val status: String,
            val launchDate: String?
        )
        
        val PARTNERSHIPS = listOf(
            // Active
            CarrierPartnership("T-Mobile", "USA", "Starlink/SpaceX", "Active", "2025-07-23"),
            CarrierPartnership("One NZ", "New Zealand", "Starlink/SpaceX", "Active", "2025-01"),
            
            // Testing/Launching
            CarrierPartnership("Telstra", "Australia", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("Optus", "Australia", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("Rogers", "Canada", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("KDDI", "Japan", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("Salt", "Switzerland", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("Entel", "Chile/Peru", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("Kyivstar", "Ukraine", "Starlink/SpaceX", "Testing", null),
            CarrierPartnership("VMO2", "UK", "Starlink/SpaceX", "Announced", null),
            CarrierPartnership("Airtel Africa", "Nigeria", "Starlink/SpaceX", "Announced", null),
            
            // Other D2D providers
            CarrierPartnership("AT&T", "USA", "AST SpaceMobile", "Testing", null),
            CarrierPartnership("Verizon", "USA", "Skylo", "Active", "2025"),
            CarrierPartnership("Orange", "France", "Skylo", "Active", "2025-12-11")
        )
    }
    
    // ========================================================================
    // ANDROID SATELLITE API REFERENCE
    // ========================================================================
    
    /**
     * Android SatelliteManager API constants and methods
     * For programmatic satellite detection
     */
    object AndroidSatelliteAPI {
        // System service name
        const val SERVICE_NAME = "satellite"
        
        // Feature flag
        const val FEATURE = "android.hardware.telephony.satellite"
        
        // NT Radio Technology constants (from SatelliteManager)
        object NTRadioTechnology {
            const val UNKNOWN = 0
            const val NB_IOT_NTN = 1      // NB-IoT over NTN
            const val NR_NTN = 2           // 5G NR over NTN
            const val EMTC_NTN = 3         // eMTC over NTN  
            const val PROPRIETARY = 4      // Proprietary (Apple/Globalstar)
        }
        
        // Satellite modem states
        object ModemState {
            const val IDLE = 0
            const val LISTENING = 1
            const val DATAGRAM_TRANSFERRING = 2
            const val DATAGRAM_RETRYING = 3
            const val OFF = 4
            const val UNAVAILABLE = 5
            const val NOT_CONNECTED = 6
            const val CONNECTED = 7
            const val ENABLING_SATELLITE = 8
            const val DISABLING_SATELLITE = 9
            const val UNKNOWN = -1
        }
        
        // Datagram types
        object DatagramType {
            const val UNKNOWN = 0
            const val SOS_MESSAGE = 1
            const val LOCATION_SHARING = 2
            const val KEEP_ALIVE = 3
            const val LAST_SOS_STILL_NEED_HELP = 4
            const val LAST_SOS_NO_HELP_NEEDED = 5
            const val SMS = 6
            const val CHECK_PENDING_SMS = 7
        }
        
        // Result codes
        object Result {
            const val SUCCESS = 0
            const val ERROR = 1
            const val SERVER_ERROR = 2
            const val SERVICE_ERROR = 3
            const val MODEM_ERROR = 4
            const val NETWORK_ERROR = 5
            const val NOT_SUPPORTED = 20
        }
        
        // Key permissions
        val REQUIRED_PERMISSIONS = listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.SATELLITE_COMMUNICATION"  // System permission
        )
        
        // Methods available (for reflection-based access)
        val PUBLIC_METHODS = listOf(
            "registerStateChangeListener",
            "unregisterStateChangeListener"
        )
        
        val SYSTEM_METHODS = listOf(
            "requestIsSupported",
            "requestIsEnabled",
            "requestIsDemoModeEnabled",
            "requestCapabilities",
            "requestNtnSignalStrength"
        )
    }
    
    // ========================================================================
    // DETECTION RULES FOR FLOCK-YOU
    // ========================================================================
    
    /**
     * Pre-configured detection rules for the surveillance detection app
     */
    object DetectionRules {
        
        data class SatelliteDetectionRule(
            val id: String,
            val name: String,
            val description: String,
            val severity: String,
            val enabled: Boolean = true,
            val checkFunction: String
        )
        
        val RULES = listOf(
            SatelliteDetectionRule(
                id = "SAT_001",
                name = "Unexpected Satellite Switch",
                description = "Device switched to satellite despite good terrestrial signal",
                severity = "HIGH",
                checkFunction = "checkUnexpectedSatelliteSwitch"
            ),
            SatelliteDetectionRule(
                id = "SAT_002",
                name = "Unknown Satellite Network",
                description = "Connected to unrecognized satellite network",
                severity = "CRITICAL",
                checkFunction = "checkUnknownSatelliteNetwork"
            ),
            SatelliteDetectionRule(
                id = "SAT_003",
                name = "Timing Anomaly",
                description = "Satellite connection timing inconsistent with claimed orbit",
                severity = "HIGH",
                checkFunction = "checkTimingAnomaly"
            ),
            SatelliteDetectionRule(
                id = "SAT_004",
                name = "Rapid Switching",
                description = "Abnormally frequent satellite handoffs",
                severity = "MEDIUM",
                checkFunction = "checkRapidSwitching"
            ),
            SatelliteDetectionRule(
                id = "SAT_005",
                name = "Forced Downgrade",
                description = "Network forced from 5G/LTE to satellite",
                severity = "HIGH",
                checkFunction = "checkForcedDowngrade"
            ),
            SatelliteDetectionRule(
                id = "SAT_006",
                name = "Frequency Mismatch",
                description = "Satellite connection on non-NTN frequency",
                severity = "CRITICAL",
                checkFunction = "checkFrequencyMismatch"
            ),
            SatelliteDetectionRule(
                id = "SAT_007",
                name = "Urban Satellite",
                description = "Satellite connection in area with known coverage",
                severity = "MEDIUM",
                checkFunction = "checkUrbanSatellite"
            )
        )
    }
}

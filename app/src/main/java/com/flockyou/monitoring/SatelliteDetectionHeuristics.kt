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
        
        // Note: n253, n254, n255 share the same L-band frequencies but differ in:
        // - n253: Standard MSS L-band
        // - n254: MSS L-band with different duplexing
        // - n255: MSS L-band with different channel arrangements
        // For detection purposes, we treat them as equivalent L-band.
        val BANDS = listOf(
            BandDefinition("n253", 1626..1660, 1525..1559, "MSS L-band (Standard)", "Rel-17"),
            BandDefinition("n254", 1626..1660, 1525..1559, "MSS L-band (Alt Duplex)", "Rel-17"),
            BandDefinition("n255", 1626..1660, 1525..1559, "MSS L-band (Alt Channel)", "Rel-17"),
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
        
        // Get band name for frequency (returns first match for overlapping bands)
        // For L-band frequencies, returns the primary band (n253)
        fun getBandForFrequency(freqMHz: Int): String? {
            // Check S-band first (unique frequency range)
            if (freqMHz in 1980..2010 || freqMHz in 2170..2200) {
                return "n256"
            }
            // Check Ka-band ranges
            if (freqMHz in 27500..30000 || freqMHz in 17700..21200) {
                return if (freqMHz in 27500..29500 || freqMHz in 17700..20200) "n510" else "n511"
            }
            if (freqMHz in 42500..43500) {
                return "n512"
            }
            // L-band (return primary band n253 for overlapping frequencies)
            if (freqMHz in 1525..1559 || freqMHz in 1626..1660) {
                return "n253"
            }
            return null
        }

        // Get all matching bands for frequency (useful when bands overlap)
        fun getAllBandsForFrequency(freqMHz: Int): List<String> {
            return BANDS.filter { band ->
                freqMHz in band.uplinkMHz || freqMHz in band.downlinkMHz
            }.map { it.bandNumber }
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
         * Verify claimed satellite is using correct NTN bands for the specific provider
         */
        fun validateFrequencyForProvider(
            provider: String,
            frequencyMHz: Int
        ): Boolean {
            // First check if it's a valid NTN frequency at all
            if (!NTNBands.isNTNFrequency(frequencyMHz)) {
                return false
            }

            // Provider-specific validation
            val providerUpper = provider.uppercase()
            return when {
                // T-Mobile Starlink uses L-band and S-band
                providerUpper.contains("STARLINK") || providerUpper.contains("T-MOBILE") -> {
                    // L-band: 1525-1559 MHz (DL), 1626-1660 MHz (UL)
                    // S-band: 1980-2010 MHz (DL), 2170-2200 MHz (UL)
                    (frequencyMHz in 1525..1660) || (frequencyMHz in 1980..2200)
                }
                // Skylo NTN uses L-band primarily
                providerUpper.contains("SKYLO") -> {
                    frequencyMHz in 1525..1660
                }
                // Globalstar uses L-band and S-band
                providerUpper.contains("GLOBALSTAR") -> {
                    (frequencyMHz in 1610..1618) || (frequencyMHz in 2483..2500)
                }
                // Iridium uses L-band
                providerUpper.contains("IRIDIUM") -> {
                    frequencyMHz in 1616..1626
                }
                // Generic/unknown providers - accept any valid NTN band
                else -> true
            }
        }

        /**
         * Heuristic 7: Provider Frequency Band Info
         *
         * Get expected frequency bands for a provider
         */
        fun getExpectedBandsForProvider(provider: String): List<String> {
            val providerUpper = provider.uppercase()
            return when {
                providerUpper.contains("STARLINK") || providerUpper.contains("T-MOBILE") -> {
                    listOf("L-band (1525-1660 MHz)", "S-band (1980-2200 MHz)")
                }
                providerUpper.contains("SKYLO") -> {
                    listOf("L-band (1525-1660 MHz)")
                }
                providerUpper.contains("GLOBALSTAR") -> {
                    listOf("L-band (1610-1618 MHz)", "S-band (2483-2500 MHz)")
                }
                providerUpper.contains("IRIDIUM") -> {
                    listOf("L-band (1616-1626 MHz)")
                }
                else -> listOf("Unknown - any NTN band")
            }
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
    // APPLE EMERGENCY SOS VIA SATELLITE (iPhone 14+)
    // ========================================================================

    /**
     * Apple Emergency SOS via Satellite (Globalstar partnership)
     * Available on iPhone 14 and later models
     */
    object AppleEmergencySOS {
        val NETWORK_NAMES = listOf(
            "Emergency SOS",
            "Satellite SOS",
            "Globalstar Emergency"
        )

        // Partner satellite operator
        const val SATELLITE_PARTNER = "Globalstar"

        // Supported countries (as of January 2026)
        val SUPPORTED_COUNTRIES = listOf(
            "United States",
            "Canada",
            "United Kingdom",
            "France",
            "Germany",
            "Ireland",
            "Austria",
            "Belgium",
            "Italy",
            "Luxembourg",
            "Netherlands",
            "Portugal",
            "Spain",
            "Switzerland",
            "Australia",
            "New Zealand",
            "Japan"
        )

        // Supported devices
        val SUPPORTED_DEVICES = listOf(
            // iPhone 14 series
            "iPhone 14", "iPhone 14 Plus", "iPhone 14 Pro", "iPhone 14 Pro Max",
            // iPhone 15 series
            "iPhone 15", "iPhone 15 Plus", "iPhone 15 Pro", "iPhone 15 Pro Max",
            // iPhone 16 series
            "iPhone 16", "iPhone 16 Plus", "iPhone 16 Pro", "iPhone 16 Pro Max"
        )

        // Technical specifications
        object TechnicalSpecs {
            const val PROTOCOL = "Proprietary (Globalstar MSS)"
            const val CONSTELLATION_SIZE = 24  // Globalstar satellites
            const val ORBITAL_ALTITUDE_KM = 1400  // LEO

            // Capabilities
            const val SUPPORTS_EMERGENCY_SOS = true
            const val SUPPORTS_FIND_MY = true  // Find My via satellite (iPhone 14+)
            const val SUPPORTS_SMS = false     // Emergency messaging only
            const val SUPPORTS_VOICE = false

            // Service terms
            const val FREE_PERIOD_YEARS = 2  // Free for 2 years with iPhone purchase

            // Performance
            const val MESSAGE_SEND_TIME_TYPICAL_S = 15  // Under clear sky
            const val MESSAGE_SEND_TIME_MAX_S = 60      // Poor conditions
        }

        // Frequency bands (Globalstar)
        object FrequencyBands {
            // L-band (user terminal to satellite)
            const val L_BAND_START_MHZ = 1610
            const val L_BAND_END_MHZ = 1618

            // S-band (satellite to user terminal)
            const val S_BAND_START_MHZ = 2483
            const val S_BAND_END_MHZ = 2500
        }
    }

    // ========================================================================
    // LEGITIMATE SATELLITE SERVICES DATABASE
    // ========================================================================

    /**
     * Comprehensive database of legitimate satellite services
     * Used for network identification and validation
     */
    object LegitimateSatelliteServices {

        /**
         * Globalstar - LEO satellite constellation
         * Partner: Apple Emergency SOS
         */
        object Globalstar {
            const val DISPLAY_NAME = "Globalstar"
            const val CONSTELLATION_TYPE = "LEO"
            const val ORBITAL_ALTITUDE_KM = 1400
            const val SATELLITE_COUNT = 24
            const val COVERAGE = "Global (excluding extreme polar regions)"

            // Primary use cases
            val USE_CASES = listOf(
                "Apple iPhone Emergency SOS",
                "SPOT satellite messengers",
                "Asset tracking",
                "Maritime communications"
            )

            // Frequency bands
            object Frequencies {
                const val L_BAND_UL_START_MHZ = 1610
                const val L_BAND_UL_END_MHZ = 1618
                const val S_BAND_DL_START_MHZ = 2483
                const val S_BAND_DL_END_MHZ = 2500
            }

            // Expected latency
            const val TYPICAL_LATENCY_MS = 50
            const val MAX_LATENCY_MS = 120

            // Is legitimate if user is using a Globalstar device or iPhone with Emergency SOS
            fun isLegitimateUsage(context: String): Boolean {
                return context.contains("emergency", ignoreCase = true) ||
                       context.contains("SOS", ignoreCase = true) ||
                       context.contains("SPOT", ignoreCase = true) ||
                       context.contains("iPhone", ignoreCase = true)
            }
        }

        /**
         * Iridium - LEO satellite constellation
         * Only satellite network with true global coverage (including poles)
         */
        object Iridium {
            const val DISPLAY_NAME = "Iridium"
            const val CONSTELLATION_TYPE = "LEO"
            const val ORBITAL_ALTITUDE_KM = 780
            const val SATELLITE_COUNT = 66  // Plus spares
            const val COVERAGE = "Global (including polar regions)"

            // Primary use cases
            val USE_CASES = listOf(
                "Satellite phones",
                "Garmin InReach devices",
                "Maritime safety",
                "Aviation communications",
                "Military and government",
                "Remote IoT"
            )

            // Frequency band
            object Frequencies {
                const val L_BAND_START_MHZ = 1616
                const val L_BAND_END_MHZ = 1626
            }

            // Expected latency
            const val TYPICAL_LATENCY_MS = 35
            const val MAX_LATENCY_MS = 80

            // Is legitimate if user has Iridium device (satellite phone or Garmin)
            fun isLegitimateUsage(context: String): Boolean {
                return context.contains("InReach", ignoreCase = true) ||
                       context.contains("Garmin", ignoreCase = true) ||
                       context.contains("sat phone", ignoreCase = true) ||
                       context.contains("satellite phone", ignoreCase = true)
            }
        }

        /**
         * Inmarsat - GEO satellite constellation
         * Primary use: Maritime, aviation, government
         */
        object Inmarsat {
            const val DISPLAY_NAME = "Inmarsat"
            const val CONSTELLATION_TYPE = "GEO"
            const val ORBITAL_ALTITUDE_KM = 35786
            const val SATELLITE_COUNT = 14  // I-4, I-5, I-6 series
            const val COVERAGE = "Global (between 76N and 76S latitude)"

            // Primary use cases
            val USE_CASES = listOf(
                "Maritime communications (GMDSS)",
                "Aviation communications (SwiftBroadband)",
                "Government and military",
                "BGAN terminals",
                "IsatPhone handsets"
            )

            // Frequency band
            object Frequencies {
                // L-band for mobile
                const val L_BAND_START_MHZ = 1525
                const val L_BAND_END_MHZ = 1559
            }

            // Expected latency (GEO - higher due to altitude)
            const val TYPICAL_LATENCY_MS = 280
            const val MAX_LATENCY_MS = 600

            // Is legitimate if user is on a ship, aircraft, or using BGAN terminal
            fun isLegitimateUsage(context: String): Boolean {
                return context.contains("maritime", ignoreCase = true) ||
                       context.contains("ship", ignoreCase = true) ||
                       context.contains("aircraft", ignoreCase = true) ||
                       context.contains("BGAN", ignoreCase = true) ||
                       context.contains("IsatPhone", ignoreCase = true)
            }
        }

        /**
         * Thuraya - GEO satellite constellation
         * Primary coverage: Europe, Africa, Middle East, Asia, Australia
         */
        object Thuraya {
            const val DISPLAY_NAME = "Thuraya"
            const val CONSTELLATION_TYPE = "GEO"
            const val ORBITAL_ALTITUDE_KM = 35786
            const val SATELLITE_COUNT = 2  // Thuraya 2 and 3
            const val COVERAGE = "Europe, Africa, Middle East, Central Asia, South Asia, Australia"

            // Primary use cases
            val USE_CASES = listOf(
                "Satellite phones (Thuraya X5-Touch, XT-PRO)",
                "Journalists in remote areas",
                "NGO and humanitarian organizations",
                "Oil and gas operations",
                "Expeditions"
            )

            // Frequency band
            object Frequencies {
                const val L_BAND_START_MHZ = 1525
                const val L_BAND_END_MHZ = 1559
            }

            // Expected latency (GEO)
            const val TYPICAL_LATENCY_MS = 250
            const val MAX_LATENCY_MS = 500

            // Coverage check (not available in Americas)
            val NON_COVERED_REGIONS = listOf(
                "North America",
                "South America",
                "Central America"
            )
        }

        /**
         * Check if a provider is a known legitimate satellite service
         */
        fun isKnownProvider(providerName: String): Boolean {
            val knownProviders = listOf(
                "Globalstar", "Iridium", "Inmarsat", "Thuraya",
                "Starlink", "SpaceX", "T-Mobile", "Skylo",
                "AST SpaceMobile", "Lynk", "Echostar", "ViaSat"
            )
            return knownProviders.any { providerName.contains(it, ignoreCase = true) }
        }

        /**
         * Get expected frequency range for a provider
         */
        fun getExpectedFrequencyRange(providerName: String): IntRange? {
            return when {
                providerName.contains("Globalstar", ignoreCase = true) ->
                    Globalstar.Frequencies.L_BAND_UL_START_MHZ..Globalstar.Frequencies.S_BAND_DL_END_MHZ
                providerName.contains("Iridium", ignoreCase = true) ->
                    Iridium.Frequencies.L_BAND_START_MHZ..Iridium.Frequencies.L_BAND_END_MHZ
                providerName.contains("Inmarsat", ignoreCase = true) ->
                    Inmarsat.Frequencies.L_BAND_START_MHZ..Inmarsat.Frequencies.L_BAND_END_MHZ
                providerName.contains("Thuraya", ignoreCase = true) ->
                    Thuraya.Frequencies.L_BAND_START_MHZ..Thuraya.Frequencies.L_BAND_END_MHZ
                else -> null
            }
        }

        /**
         * Get expected latency range for orbit type
         */
        fun getExpectedLatencyForOrbit(orbitType: String): IntRange {
            return when (orbitType.uppercase()) {
                "LEO" -> 20..100
                "MEO" -> 80..200
                "GEO" -> 200..600
                else -> 0..Int.MAX_VALUE
            }
        }
    }

    // ========================================================================
    // SUSPICIOUS SATELLITE ACTIVITY PATTERNS
    // ========================================================================

    /**
     * Detection patterns for potential surveillance via satellite manipulation
     */
    object SuspiciousSatellitePatterns {

        /**
         * Rogue Base Station via Satellite Backhaul
         *
         * Attack scenario: A fake cell tower using satellite for backhaul
         * - Very expensive and complex to deploy
         * - Would appear as: Unknown cell + satellite connection
         * - Detection: Unusual cell combined with satellite
         */
        data class RogueBaseStationIndicators(
            val unknownCellId: Boolean,
            val hasSatelliteBackhaul: Boolean,
            val cellConfigurationSuspicious: Boolean,
            val timingMismatch: Boolean
        ) {
            fun getRiskLevel(): String {
                val indicators = listOf(
                    unknownCellId,
                    hasSatelliteBackhaul,
                    cellConfigurationSuspicious,
                    timingMismatch
                ).count { it }
                return when (indicators) {
                    4 -> "CRITICAL"
                    3 -> "HIGH"
                    2 -> "MEDIUM"
                    1 -> "LOW"
                    else -> "INFO"
                }
            }
        }

        /**
         * Satellite Timing Manipulation
         *
         * Attack scenario: Manipulating satellite timing to enable MITM
         * - Satellites provide timing for cellular networks
         * - Manipulation could desync the device
         * - Detection: Compare NTP time, GNSS time, cell time
         */
        data class TimingManipulationIndicators(
            val ntpTimeDifferenceMs: Long,
            val gnssTimeDifferenceMs: Long,
            val cellTimeDifferenceMs: Long,
            val claimedLatencyMs: Long,
            val measuredLatencyMs: Long
        ) {
            fun isSuspicious(): Boolean {
                // Check for time source conflicts
                val timeSourceConflict = ntpTimeDifferenceMs > 1000 ||
                                         gnssTimeDifferenceMs > 1000 ||
                                         cellTimeDifferenceMs > 500

                // Check for latency anomaly (claimed vs measured)
                val latencyAnomaly = kotlin.math.abs(claimedLatencyMs - measuredLatencyMs) > 50

                return timeSourceConflict || latencyAnomaly
            }

            fun getAnalysis(): String {
                val issues = mutableListOf<String>()
                if (ntpTimeDifferenceMs > 1000) issues.add("NTP time deviation: ${ntpTimeDifferenceMs}ms")
                if (gnssTimeDifferenceMs > 1000) issues.add("GNSS time deviation: ${gnssTimeDifferenceMs}ms")
                if (cellTimeDifferenceMs > 500) issues.add("Cell time deviation: ${cellTimeDifferenceMs}ms")
                if (kotlin.math.abs(claimedLatencyMs - measuredLatencyMs) > 50) {
                    issues.add("Latency mismatch: claimed ${claimedLatencyMs}ms vs measured ${measuredLatencyMs}ms")
                }
                return if (issues.isEmpty()) "No timing anomalies detected"
                       else "Timing anomalies: ${issues.joinToString("; ")}"
            }
        }

        /**
         * Forced Satellite Downgrade Attack
         *
         * Attack scenario: Jammer blocks terrestrial to force satellite connection
         * - Attacker jams local cellular towers
         * - Device falls back to satellite (may be easier to intercept)
         * - Detection: Good terrestrial signal suddenly lost, then satellite
         */
        data class ForcedDowngradeAttackIndicators(
            val hadGoodTerrestrialSignalDbm: Int?,
            val terrestrialSignalDroppedSuddenlyMs: Long?,
            val forcedToSatelliteWithinMs: Long?,
            val multipleDevicesAffected: Boolean,
            val locationHasKnownCoverage: Boolean
        ) {
            fun isSuspicious(): Boolean {
                // Had good signal (-90 dBm or better) that dropped suddenly
                val hadGoodSignal = (hadGoodTerrestrialSignalDbm ?: -120) > -90
                val droppedSuddenly = (terrestrialSignalDroppedSuddenlyMs ?: Long.MAX_VALUE) < 5000
                val forcedQuickly = (forcedToSatelliteWithinMs ?: Long.MAX_VALUE) < 30000

                return hadGoodSignal && droppedSuddenly && forcedQuickly && locationHasKnownCoverage
            }

            fun getSeverity(): String {
                return when {
                    isSuspicious() && multipleDevicesAffected -> "CRITICAL"
                    isSuspicious() -> "HIGH"
                    hadGoodTerrestrialSignalDbm != null && hadGoodTerrestrialSignalDbm > -90 -> "MEDIUM"
                    else -> "LOW"
                }
            }
        }

        /**
         * Ground-Based Satellite Spoofing
         *
         * Attack scenario: Ground equipment pretending to be satellite
         * - Latency will be too low (<20ms is impossible from space)
         * - Signal may be too strong
         * - Detection: RTT analysis, signal strength analysis
         */
        data class GroundSpoofingIndicators(
            val rttMs: Long,
            val claimedOrbitType: String,  // LEO, MEO, GEO
            val signalStrengthDbm: Int,
            val dopplerShiftHz: Double?
        ) {
            // Minimum RTT from space (light travel time + processing)
            // LEO (500km): min ~3.3ms one way, ~7ms round trip + processing = ~15-20ms minimum
            // MEO (20,000km): min ~130ms
            // GEO (36,000km): min ~240ms

            fun isLikelyGroundBased(): Boolean {
                val minRttForOrbit = when (claimedOrbitType.uppercase()) {
                    "LEO" -> 15L
                    "MEO" -> 100L
                    "GEO" -> 200L
                    else -> 10L
                }

                // RTT too fast for claimed orbit = ground-based
                if (rttMs < minRttForOrbit) return true

                // Signal too strong for satellite (-70 dBm or better is suspicious)
                if (signalStrengthDbm > -70) return true

                // No Doppler shift on LEO/MEO is suspicious (satellites move fast)
                if (dopplerShiftHz != null && claimedOrbitType in listOf("LEO", "MEO")) {
                    if (kotlin.math.abs(dopplerShiftHz) < 1000) return true  // Too little shift
                }

                return false
            }

            fun getAnalysis(): String {
                val issues = mutableListOf<String>()

                val minRtt = when (claimedOrbitType.uppercase()) {
                    "LEO" -> 15
                    "MEO" -> 100
                    "GEO" -> 200
                    else -> 10
                }

                if (rttMs < minRtt) {
                    issues.add("RTT ${rttMs}ms is physically impossible for ${claimedOrbitType} orbit (min ${minRtt}ms)")
                }

                if (signalStrengthDbm > -70) {
                    issues.add("Signal ${signalStrengthDbm}dBm is unusually strong for satellite")
                }

                if (dopplerShiftHz != null && claimedOrbitType in listOf("LEO", "MEO")) {
                    if (kotlin.math.abs(dopplerShiftHz) < 1000) {
                        issues.add("Doppler shift ${dopplerShiftHz}Hz is too low for moving satellite")
                    }
                }

                return if (issues.isEmpty()) "Parameters consistent with satellite"
                       else "SUSPICIOUS: ${issues.joinToString("; ")}"
            }
        }
    }

    // ========================================================================
    // REAL-WORLD CONFIRMATION METHODS FOR USERS
    // ========================================================================

    /**
     * User-friendly confirmation methods to validate satellite anomalies
     */
    object UserConfirmationMethods {

        /**
         * Visual confirmation checks users can perform
         */
        val VISUAL_CHECKS = listOf(
            UserConfirmationStep(
                id = "CHECK_PHONE_ICON",
                instruction = "Check if your phone shows a satellite icon in the status bar",
                expectedNormal = "Satellite icon should only appear when you have no cellular signal",
                suspiciousIf = "Satellite icon appears while you have strong cellular bars"
            ),
            UserConfirmationStep(
                id = "CHECK_SKY_VISIBILITY",
                instruction = "Look around - do you have a clear view of the sky?",
                expectedNormal = "Satellite connections require direct sky visibility",
                suspiciousIf = "You're indoors, in a basement, or surrounded by tall buildings"
            ),
            UserConfirmationStep(
                id = "CHECK_CELLULAR_SETTINGS",
                instruction = "Go to Settings > Network/Cellular > Check your connection type",
                expectedNormal = "Should show LTE, 5G, or similar when in normal coverage",
                suspiciousIf = "Shows satellite/NTN when you're in an urban area with good coverage"
            )
        )

        /**
         * Environmental verification steps
         */
        val ENVIRONMENTAL_CHECKS = listOf(
            UserConfirmationStep(
                id = "CHECK_LOCATION_TYPE",
                instruction = "Consider your current location type",
                expectedNormal = "Satellite is expected in: wilderness, rural areas, at sea, on aircraft",
                suspiciousIf = "Satellite connection in: downtown, shopping mall, office building, home with WiFi"
            ),
            UserConfirmationStep(
                id = "ASK_OTHERS_NEARBY",
                instruction = "Ask others nearby if they have cellular signal",
                expectedNormal = "If others have good signal, you should too",
                suspiciousIf = "Only your device is forced to satellite while others have normal service"
            ),
            UserConfirmationStep(
                id = "CHECK_COVERAGE_MAP",
                instruction = "Check cellmapper.net or your carrier's coverage map for your location",
                expectedNormal = "Map shows good coverage = you should have terrestrial connection",
                suspiciousIf = "Map shows excellent coverage but your device uses satellite"
            )
        )

        /**
         * Technical verification steps
         */
        val TECHNICAL_CHECKS = listOf(
            UserConfirmationStep(
                id = "MOVE_TO_KNOWN_COVERAGE",
                instruction = "Move to a location with known good cellular coverage",
                expectedNormal = "Device should switch back to terrestrial network quickly",
                suspiciousIf = "Device stays on satellite even in areas with excellent coverage"
            ),
            UserConfirmationStep(
                id = "TOGGLE_AIRPLANE_MODE",
                instruction = "Turn airplane mode on for 10 seconds, then off",
                expectedNormal = "Device should reconnect to best available network (usually terrestrial)",
                suspiciousIf = "Device immediately reconnects to satellite despite better options"
            ),
            UserConfirmationStep(
                id = "CHECK_LATENCY",
                instruction = "Run a speed test or ping test",
                expectedNormal = "LEO satellite: 20-80ms, GEO: 250-600ms latency",
                suspiciousIf = "Claimed satellite with <20ms latency (impossible from space)"
            )
        )

        /**
         * Get all confirmation steps for a specific anomaly type
         */
        fun getConfirmationStepsForAnomaly(anomalyType: String): List<UserConfirmationStep> {
            return when {
                anomalyType.contains("UNEXPECTED", ignoreCase = true) ->
                    VISUAL_CHECKS + ENVIRONMENTAL_CHECKS
                anomalyType.contains("TIMING", ignoreCase = true) ->
                    TECHNICAL_CHECKS
                anomalyType.contains("INDOOR", ignoreCase = true) ->
                    VISUAL_CHECKS.filter { it.id == "CHECK_SKY_VISIBILITY" } + ENVIRONMENTAL_CHECKS
                anomalyType.contains("DOWNGRADE", ignoreCase = true) ->
                    ENVIRONMENTAL_CHECKS + TECHNICAL_CHECKS
                else -> VISUAL_CHECKS + ENVIRONMENTAL_CHECKS + TECHNICAL_CHECKS
            }
        }
    }

    data class UserConfirmationStep(
        val id: String,
        val instruction: String,
        val expectedNormal: String,
        val suspiciousIf: String
    )

    // ========================================================================
    // ENVIRONMENTAL CONTEXT FOR SATELLITE USAGE
    // ========================================================================

    /**
     * Environmental context to help determine if satellite usage is expected
     */
    object EnvironmentalContext {

        /**
         * Location types where satellite is EXPECTED (not suspicious)
         */
        enum class SatelliteExpectedLocation(
            val description: String,
            val examples: List<String>
        ) {
            AIRCRAFT(
                "On commercial or private aircraft",
                listOf("In-flight", "Aviation", "Above 10,000ft")
            ),
            MARITIME(
                "On ships or boats at sea",
                listOf("Cruise ship", "Cargo vessel", "Yacht", "Ferry in open water")
            ),
            REMOTE_WILDERNESS(
                "Remote wilderness areas",
                listOf("National parks", "Mountain trails", "Desert", "Arctic/Antarctic")
            ),
            RURAL_NO_COVERAGE(
                "Rural areas with no cellular coverage",
                listOf("Farmland", "Remote ranches", "Isolated communities")
            ),
            EMERGENCY_SITUATION(
                "Emergency situations with damaged infrastructure",
                listOf("Natural disaster zone", "Post-hurricane", "Earthquake affected area")
            )
        }

        /**
         * Location types where satellite is SUSPICIOUS
         */
        enum class SatelliteSuspiciousLocation(
            val description: String,
            val whySuspicious: String
        ) {
            DOWNTOWN_URBAN(
                "Downtown urban areas",
                "Excellent cellular coverage expected; satellite suggests forced downgrade"
            ),
            SHOPPING_MALL(
                "Inside shopping malls or large buildings",
                "No sky visibility for satellite; impossible to receive signal"
            ),
            BASEMENT_UNDERGROUND(
                "Basements or underground locations",
                "Physically impossible for satellite signal to reach"
            ),
            OFFICE_BUILDING(
                "Inside office buildings",
                "Good indoor coverage expected; no satellite signal possible"
            ),
            RESIDENTIAL_URBAN(
                "Urban residential areas",
                "Good coverage expected; satellite connection is unusual"
            ),
            SUBWAY_METRO(
                "Subway or metro stations",
                "Underground = no satellite possible; cellular DAS expected"
            )
        }

        /**
         * Determine if satellite usage is expected given context
         */
        fun isSatelliteExpected(
            isIndoors: Boolean,
            hasSkyClearance: Boolean,
            isUrbanArea: Boolean,
            hasTerrestrialSignal: Boolean,
            isOnAircraftOrShip: Boolean,
            isEmergencySituation: Boolean
        ): SatelliteExpectation {
            // Satellite IS expected
            if (isOnAircraftOrShip) {
                return SatelliteExpectation(
                    isExpected = true,
                    reason = "Satellite is normal for aircraft and maritime environments",
                    threatLevel = "INFO"
                )
            }

            if (isEmergencySituation) {
                return SatelliteExpectation(
                    isExpected = true,
                    reason = "Emergency SOS via satellite is a legitimate safety feature",
                    threatLevel = "INFO"
                )
            }

            if (!isUrbanArea && !hasTerrestrialSignal && hasSkyClearance) {
                return SatelliteExpectation(
                    isExpected = true,
                    reason = "No terrestrial coverage in remote area; satellite is expected",
                    threatLevel = "INFO"
                )
            }

            // Satellite is SUSPICIOUS
            if (isIndoors && !hasSkyClearance) {
                return SatelliteExpectation(
                    isExpected = false,
                    reason = "Satellite connection impossible indoors without sky visibility",
                    threatLevel = "HIGH"
                )
            }

            if (isUrbanArea && hasTerrestrialSignal) {
                return SatelliteExpectation(
                    isExpected = false,
                    reason = "Urban area with good terrestrial coverage; satellite is unusual",
                    threatLevel = "MEDIUM"
                )
            }

            // Uncertain
            return SatelliteExpectation(
                isExpected = null,
                reason = "Context unclear; monitor for additional indicators",
                threatLevel = "LOW"
            )
        }
    }

    data class SatelliteExpectation(
        val isExpected: Boolean?,  // null = uncertain
        val reason: String,
        val threatLevel: String
    )

    // ========================================================================
    // LLM/AI ANALYSIS CONTEXT
    // ========================================================================

    /**
     * Context and explanations for AI/LLM analysis of satellite anomalies
     */
    object LLMAnalysisContext {

        /**
         * Why satellite surveillance is concerning
         */
        val SURVEILLANCE_CONCERNS = """
            |## Why Satellite-Based Surveillance is Concerning
            |
            |1. **Harder to Audit**: Satellite infrastructure is controlled by a small number of operators,
            |   making it difficult to audit for surveillance capabilities compared to terrestrial networks.
            |
            |2. **Forced Downgrade Risk**: An attacker could jam terrestrial signals to force a device onto
            |   satellite, where interception may be easier or less regulated.
            |
            |3. **Cross-Border Implications**: Satellite communications may traverse different jurisdictions
            |   with varying legal protections for communications privacy.
            |
            |4. **Less Encryption Scrutiny**: NTN protocols are newer and may have implementation weaknesses
            |   that haven't been as thoroughly audited as mature terrestrial protocols.
            |
            |5. **Location Tracking**: Satellite connections require precise location data for pointing,
            |   which could be exploited for tracking if the system is compromised.
        """.trimMargin()

        /**
         * Legitimate use cases (to avoid false positives)
         */
        val LEGITIMATE_USE_CASES = """
            |## Legitimate Satellite Connection Scenarios
            |
            |Satellite connectivity is NOT inherently suspicious. Normal scenarios include:
            |
            |1. **Remote Areas**: Hikers, campers, and travelers in areas without cellular coverage
            |2. **Maritime**: Ships at sea rely on satellite for all communications
            |3. **Aviation**: Aircraft use satellite for passenger connectivity and cockpit comms
            |4. **Emergency SOS**: iPhone and Pixel devices can send emergency messages via satellite
            |5. **Natural Disasters**: When terrestrial infrastructure is damaged
            |6. **Rural Areas**: Farms, ranches, and rural communities with poor cellular coverage
            |7. **International Roaming**: Some areas lack roaming agreements but have satellite coverage
            |
            |**Key Question**: Is satellite the ONLY viable connection option for this location and time?
        """.trimMargin()

        /**
         * What makes satellite activity suspicious
         */
        val SUSPICIOUS_INDICATORS = """
            |## Indicators of Suspicious Satellite Activity
            |
            |**High Confidence Indicators (likely surveillance/spoofing):**
            |- RTT latency < 20ms on claimed satellite connection (physically impossible)
            |- Satellite connection while indoors/underground (signal cannot reach)
            |- Unknown satellite provider not matching any known operator
            |- NTN band frequencies outside allocated spectrum
            |- Immediate satellite handoff after phone call (targeting pattern)
            |
            |**Medium Confidence Indicators (investigate further):**
            |- Satellite connection in urban area with excellent coverage
            |- Rapid switching between satellite and terrestrial
            |- Signal strength too strong for satellite (-70 dBm or better)
            |- Claimed GEO satellite with LEO timing, or vice versa
            |- HARQ processes configured for terrestrial instead of NTN
            |
            |**Low Confidence Indicators (may be normal):**
            |- Satellite connection in semi-rural area
            |- Temporary satellite during network congestion
            |- Satellite during carrier maintenance window
            |
            |**Analysis Approach**: Look for MULTIPLE indicators occurring together.
            |A single indicator may be benign; several together increase concern.
        """.trimMargin()

        /**
         * How to explain findings to users
         */
        val USER_EXPLANATION_TEMPLATE = """
            |## Explaining Satellite Anomaly to User
            |
            |When communicating findings, consider:
            |
            |1. **Context First**: Explain what normal satellite behavior looks like
            |2. **Specific Observation**: What exactly was detected that's unusual
            |3. **Possible Explanations**: Both benign and concerning interpretations
            |4. **Confidence Level**: How certain we are this is malicious
            |5. **Recommended Actions**: What the user can do to protect themselves
            |
            |**Avoid**:
            |- Technical jargon without explanation
            |- Alarming the user unnecessarily
            |- Presenting low-confidence findings as definite threats
            |- Ignoring legitimate explanations
            |
            |**Example balanced explanation**:
            |"Your device connected to satellite service despite having good cellular signal.
            |This is unusual because satellite is typically only used when no other option exists.
            |While this could indicate a potential interception attempt, it might also be a
            |temporary network routing decision. We recommend monitoring for recurrence and
            |avoiding sensitive communications until the behavior stops."
        """.trimMargin()

        /**
         * Technical reference for LLM
         */
        val TECHNICAL_REFERENCE = """
            |## Technical Reference for Satellite Analysis
            |
            |### Orbit Types and Expected Latencies
            |- **LEO** (Low Earth Orbit, 200-2000km): 20-80ms RTT
            |  - Examples: Starlink (540km), Iridium (780km), Globalstar (1400km)
            |  - Satellites move fast, typical pass: 10-15 minutes
            |  - Doppler shift: 20-50 kHz due to high velocity
            |
            |- **MEO** (Medium Earth Orbit, 2000-35786km): 80-200ms RTT
            |  - Examples: O3b (8000km), GPS (20200km)
            |  - Longer passes, moderate Doppler
            |
            |- **GEO** (Geostationary, 35786km): 250-600ms RTT
            |  - Examples: Inmarsat, ViaSat
            |  - Satellite appears stationary, no Doppler from orbital motion
            |  - Cannot cover polar regions (above ~76 latitude)
            |
            |### 3GPP NTN Frequency Bands
            |- **L-band** (n253/n254/n255): 1525-1559 MHz DL, 1626-1660 MHz UL
            |- **S-band** (n256): 1980-2010 MHz UL, 2170-2200 MHz DL
            |- **Ka-band** (n510/n511, future): 17.7-20.2 GHz DL, 27.5-30 GHz UL
            |
            |### Provider Identification
            |- **T-Mobile Starlink**: "T-Mobile SpaceX", "T-Satellite", MCC 310 MNC 260
            |- **Skylo (Pixel)**: "Skylo", "Satellite SOS", NB-IoT NTN
            |- **Apple/Globalstar**: Emergency SOS only, proprietary protocol
            |
            |### Physical Constraints
            |- Light speed: ~3.3 microseconds per km (one way)
            |- Min RTT from LEO (500km): ~3.3ms physics + ~15ms processing = ~18ms
            |- Min RTT from GEO (36000km): ~240ms physics + processing = ~280ms
            |- Satellite signal CANNOT penetrate indoors without direct sky view
        """.trimMargin()

        /**
         * Get analysis prompt for a specific anomaly
         */
        fun getAnalysisPrompt(
            anomalyType: String,
            anomalyDescription: String,
            technicalDetails: Map<String, Any>
        ): String {
            return """
                |# Satellite Anomaly Analysis Request
                |
                |## Anomaly Type
                |$anomalyType
                |
                |## Description
                |$anomalyDescription
                |
                |## Technical Details
                |${technicalDetails.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }}
                |
                |## Context
                |$LEGITIMATE_USE_CASES
                |
                |$SUSPICIOUS_INDICATORS
                |
                |## Analysis Required
                |1. Is this anomaly consistent with legitimate satellite use or potential surveillance?
                |2. What is the confidence level of this assessment (LOW/MEDIUM/HIGH)?
                |3. What additional information would help clarify the situation?
                |4. What immediate actions should the user consider?
                |
                |Please provide a balanced analysis considering both benign and malicious explanations.
            """.trimMargin()
        }
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
    // GNSS ENVIRONMENTAL FALSE POSITIVE CONTEXT
    // ========================================================================

    /**
     * Environmental false positive context for GNSS anomaly detection.
     *
     * Many GNSS "anomalies" are actually normal environmental effects.
     * This database helps distinguish real attacks from false positives.
     */
    object GnssEnvironmentContext {

        /**
         * Environment types that commonly cause GNSS anomalies
         */
        enum class EnvironmentType(
            val displayName: String,
            val description: String,
            val expectedSignalLoss: Float,  // 0-1, expected signal attenuation
            val multipathLikelihood: Float,  // 0-1, likelihood of multipath
            val falsePositiveRisk: String,
            val userGuidance: String
        ) {
            URBAN_CANYON(
                displayName = "Urban Canyon",
                description = "Tall buildings on both sides of street, common in downtown areas",
                expectedSignalLoss = 0.3f,
                multipathLikelihood = 0.9f,
                falsePositiveRisk = "HIGH - Multipath causes signal variations that look like spoofing",
                userGuidance = "Move to open area or intersection for accurate GNSS assessment"
            ),
            INDOOR(
                displayName = "Indoor",
                description = "Inside a building - signals heavily attenuated",
                expectedSignalLoss = 0.8f,
                multipathLikelihood = 0.7f,
                falsePositiveRisk = "VERY HIGH - Weak/no signal is normal, not jamming",
                userGuidance = "Go outside for GNSS assessment - indoor signal loss is expected"
            ),
            PARKING_GARAGE(
                displayName = "Parking Garage",
                description = "Underground or multi-story structure with concrete above",
                expectedSignalLoss = 0.95f,
                multipathLikelihood = 0.5f,
                falsePositiveRisk = "VERY HIGH - Near-total signal loss is normal",
                userGuidance = "Exit parking structure before assessing GNSS - concrete blocks signals"
            ),
            TUNNEL(
                displayName = "Tunnel/Underpass",
                description = "Road tunnel or underpass",
                expectedSignalLoss = 1.0f,
                multipathLikelihood = 0.0f,
                falsePositiveRisk = "VERY HIGH - Complete signal loss is expected",
                userGuidance = "Signal will return after exiting tunnel - this is normal"
            ),
            NEAR_WATER(
                displayName = "Near Water",
                description = "Lake, ocean, river, or other large water body",
                expectedSignalLoss = 0.0f,
                multipathLikelihood = 0.8f,
                falsePositiveRisk = "MODERATE - Specular reflections cause multipath",
                userGuidance = "Water reflects GPS signals strongly - multipath is normal here"
            ),
            FOREST(
                displayName = "Dense Forest",
                description = "Heavily forested area with tree canopy",
                expectedSignalLoss = 0.4f,
                multipathLikelihood = 0.5f,
                falsePositiveRisk = "MODERATE - Signal attenuation of 10-20 dB expected",
                userGuidance = "Forest canopy attenuates signals - move to clearing for better reception"
            ),
            MOUNTAIN_VALLEY(
                displayName = "Mountain Valley",
                description = "Valley surrounded by mountains limiting sky visibility",
                expectedSignalLoss = 0.2f,
                multipathLikelihood = 0.3f,
                falsePositiveRisk = "LOW - Reduced satellite visibility is expected",
                userGuidance = "Terrain limits visible satellites - this affects DOP but is normal"
            ),
            OPEN_SKY(
                displayName = "Open Sky",
                description = "Open area with full sky visibility",
                expectedSignalLoss = 0.0f,
                multipathLikelihood = 0.1f,
                falsePositiveRisk = "LOW - Anomalies here are more likely real",
                userGuidance = "Good environment for GNSS assessment - anomalies warrant investigation"
            ),
            AIRPORT(
                displayName = "Airport",
                description = "Commercial or military airport",
                expectedSignalLoss = 0.0f,
                multipathLikelihood = 0.2f,
                falsePositiveRisk = "VARIABLE - Some airports have GPS testing/jamming",
                userGuidance = "Some airports have legitimate GPS interference - check with airport authority"
            ),
            MILITARY_FACILITY(
                displayName = "Near Military Facility",
                description = "Within range of military installation",
                expectedSignalLoss = 0.0f,
                multipathLikelihood = 0.1f,
                falsePositiveRisk = "LOW - Military GPS interference is real, not false positive",
                userGuidance = "Military installations may have legitimate GPS interference - document and report"
            )
        }

        /**
         * Heuristics for detecting current environment type
         */
        data class EnvironmentDetectionResult(
            val likelyEnvironment: EnvironmentType,
            val confidence: Float,  // 0-1
            val indicators: List<String>,
            val suppressAnomalies: Boolean,
            val suppressionReason: String?
        )

        /**
         * Detect likely environment based on GNSS characteristics
         */
        fun detectEnvironment(
            avgSignalStrength: Double,
            signalVariance: Double,
            satellitesVisible: Int,
            satellitesUsedInFix: Int,
            multipathIndicatorRatio: Float,
            hasRecentPosition: Boolean
        ): EnvironmentDetectionResult {

            // Indoor detection: very weak signals, few satellites
            if (avgSignalStrength < 20.0 && satellitesVisible < 6) {
                return EnvironmentDetectionResult(
                    likelyEnvironment = EnvironmentType.INDOOR,
                    confidence = 0.8f,
                    indicators = listOf(
                        "Very weak signal strength (${String.format("%.1f", avgSignalStrength)} dB-Hz)",
                        "Few satellites visible ($satellitesVisible)",
                        "Likely indoor environment"
                    ),
                    suppressAnomalies = true,
                    suppressionReason = "Indoor environment detected - signal loss is expected, not jamming"
                )
            }

            // Parking garage/tunnel: near-zero signal
            if (avgSignalStrength < 15.0 && satellitesVisible < 3) {
                return EnvironmentDetectionResult(
                    likelyEnvironment = EnvironmentType.PARKING_GARAGE,
                    confidence = 0.7f,
                    indicators = listOf(
                        "Near-zero signal strength",
                        "Almost no satellites visible",
                        "Likely parking garage or tunnel"
                    ),
                    suppressAnomalies = true,
                    suppressionReason = "Parking garage/tunnel detected - complete signal loss is normal"
                )
            }

            // Urban canyon: high multipath, moderate signal
            if (multipathIndicatorRatio > 0.5f && signalVariance > 5.0 && satellitesUsedInFix >= 4) {
                return EnvironmentDetectionResult(
                    likelyEnvironment = EnvironmentType.URBAN_CANYON,
                    confidence = 0.75f,
                    indicators = listOf(
                        "High multipath ratio (${String.format("%.0f", multipathIndicatorRatio * 100)}%)",
                        "High signal variance",
                        "Position fix maintained despite multipath"
                    ),
                    suppressAnomalies = true,
                    suppressionReason = "Urban canyon detected - multipath is normal in cities"
                )
            }

            // Forest: moderate signal loss, some multipath
            if (avgSignalStrength in 20.0..30.0 && signalVariance in 2.0..6.0) {
                return EnvironmentDetectionResult(
                    likelyEnvironment = EnvironmentType.FOREST,
                    confidence = 0.5f,
                    indicators = listOf(
                        "Moderate signal attenuation",
                        "Moderate signal variance",
                        "Possible forest or light obstruction"
                    ),
                    suppressAnomalies = false,  // Don't automatically suppress
                    suppressionReason = null
                )
            }

            // Open sky: good signals, low multipath
            if (avgSignalStrength > 35.0 && satellitesUsedInFix >= 10 && multipathIndicatorRatio < 0.2f) {
                return EnvironmentDetectionResult(
                    likelyEnvironment = EnvironmentType.OPEN_SKY,
                    confidence = 0.85f,
                    indicators = listOf(
                        "Strong signal strength (${String.format("%.1f", avgSignalStrength)} dB-Hz)",
                        "Many satellites used in fix ($satellitesUsedInFix)",
                        "Low multipath - good GNSS environment"
                    ),
                    suppressAnomalies = false,
                    suppressionReason = null  // Anomalies in open sky warrant attention
                )
            }

            // Default: uncertain environment
            return EnvironmentDetectionResult(
                likelyEnvironment = EnvironmentType.OPEN_SKY,  // Default assumption
                confidence = 0.3f,
                indicators = listOf(
                    "Environment uncertain",
                    "Signal: ${String.format("%.1f", avgSignalStrength)} dB-Hz",
                    "Satellites: $satellitesUsedInFix used"
                ),
                suppressAnomalies = false,
                suppressionReason = null
            )
        }

        /**
         * Suppression duration after indoor detection
         * (Don't report anomalies for 2 minutes after being indoors)
         */
        const val POST_INDOOR_SUPPRESSION_MS = 120_000L

        /**
         * Time-based suppression rules
         */
        data class TemporalSuppressionRule(
            val afterEnvironment: EnvironmentType,
            val suppressionDurationMs: Long,
            val reason: String
        )

        val TEMPORAL_SUPPRESSION_RULES = listOf(
            TemporalSuppressionRule(
                afterEnvironment = EnvironmentType.INDOOR,
                suppressionDurationMs = 120_000L,  // 2 minutes
                reason = "Recent indoor activity - GNSS may still be re-acquiring"
            ),
            TemporalSuppressionRule(
                afterEnvironment = EnvironmentType.PARKING_GARAGE,
                suppressionDurationMs = 60_000L,  // 1 minute
                reason = "Recently exited parking structure - allow time for re-acquisition"
            ),
            TemporalSuppressionRule(
                afterEnvironment = EnvironmentType.TUNNEL,
                suppressionDurationMs = 30_000L,  // 30 seconds
                reason = "Recently exited tunnel - brief re-acquisition period"
            )
        )
    }

    // ========================================================================
    // LLM RESEARCH CONTEXT FOR GNSS ANALYSIS
    // ========================================================================

    /**
     * Technical background information for LLM-based analysis.
     *
     * This section provides detailed technical context that an LLM can use
     * to provide more accurate and educational responses about GNSS anomalies.
     */
    object GnssLLMResearchContext {

        /**
         * Ephemeris data explanation
         */
        const val EPHEMERIS_EXPLANATION = """
Ephemeris data contains precise orbital parameters for each satellite, allowing the receiver
to calculate satellite positions at any given time.

KEY POINTS:
- Valid for approximately 4 hours (must be periodically refreshed)
- Broadcast by each satellite in 30-second frames
- Contains: satellite position (X, Y, Z), velocity, clock corrections
- Cold start requires ~30 seconds to download ephemeris
- Hot start uses cached ephemeris for faster fix

SPOOFING RELEVANCE:
- Spoofed signals must provide valid ephemeris to be believable
- Stale ephemeris (wrong satellite positions) can be detected
- Cross-check predicted vs actual satellite positions
- Ephemeris age > 4 hours is suspicious if receiver claims fresh data
"""

        /**
         * PRN codes explanation
         */
        const val PRN_CODE_EXPLANATION = """
PRN (Pseudo-Random Noise) codes are unique binary sequences that identify each satellite.

KEY POINTS:
- GPS C/A code: 1023 chips, 1 millisecond period
- Each satellite has unique PRN (PRN 1-32 for GPS)
- Receiver correlates received signal with known PRN codes
- Correlation peak indicates satellite presence and timing

SPOOFING RELEVANCE:
- Spoofer must generate correct PRN for claimed satellite
- GPS C/A codes are publicly documented (easy to spoof)
- Military P(Y) codes are encrypted (very hard to spoof)
- Galileo OSNMA adds cryptographic authentication to PRN

HOW DETECTION WORKS:
- If multiple "satellites" have identical PRN correlation = spoofing
- PRN code phase can reveal timing manipulation
- Cross-correlation between multiple receivers can detect spoofed PRN
"""

        /**
         * Multi-constellation advantages explanation
         */
        const val MULTI_CONSTELLATION_EXPLANATION = """
Modern receivers use multiple GNSS constellations for improved performance and integrity.

AVAILABLE CONSTELLATIONS:
- GPS (USA): 31 satellites, L1/L2/L5 frequencies
- GLONASS (Russia): 24 satellites, FDMA-based, G1/G2/G3 frequencies
- Galileo (EU): 30 satellites, E1/E5/E6 frequencies, has OSNMA authentication
- BeiDou (China): 45+ satellites, B1/B2/B3 frequencies
- QZSS (Japan): 4 satellites, regional augmentation
- IRNSS/NavIC (India): 7 satellites, regional system

SPOOFING DEFENSE:
- Attacker must spoof ALL constellations coherently
- Different frequencies make simultaneous spoofing difficult
- GLONASS uses FDMA (frequency division) vs CDMA - different spoofing approach needed
- Cross-constellation position comparison can detect inconsistencies
- If GPS is anomalous but GLONASS is normal = selective attack
"""

        /**
         * RAIM explanation
         */
        const val RAIM_EXPLANATION = """
RAIM (Receiver Autonomous Integrity Monitoring) is a built-in integrity check.

HOW IT WORKS:
- Requires 5+ satellites to DETECT a single faulty signal
- Requires 6+ satellites to EXCLUDE the faulty signal
- Computes position using different satellite subsets
- Inconsistent subsets indicate faulty measurement
- Provides Horizontal Protection Level (HPL) estimate

LIMITATIONS:
- Cannot detect multiple simultaneous faults
- Assumes at most one satellite is faulty
- May not detect sophisticated coordinated spoofing
- Performance depends on satellite geometry (DOP)

ADVANCED RAIM (ARAIM):
- Uses dual-frequency measurements
- Includes satellite clock/ephemeris error bounds
- Provides vertical protection levels for aviation
"""

        /**
         * Signal strength physics
         */
        const val SIGNAL_STRENGTH_PHYSICS = """
GNSS signal strength is governed by the link budget equation.

SPACE-TO-EARTH PATH:
- Satellite transmit power: ~50W (GPS)
- Free space path loss: ~180 dB at 20,200 km
- Received power at Earth: ~-160 dBW (very weak!)
- Equivalent to a 25-watt bulb viewed from 10,000 miles

ELEVATION EFFECTS:
- High elevation (>60 deg): shortest path, strongest signal
- Low elevation (<10 deg): longest path through atmosphere, weakest signal
- Typical variation: 10-20 dB across sky
- Signal at 5 degrees elevation should be 10-15 dB weaker than at 60 degrees

SPOOFING SIGNATURES:
- All satellites same strength = single terrestrial transmitter
- Low elevation satellite with high signal = physically impossible without nearby source
- Signal > 55 dB-Hz suggests terrestrial transmitter (too strong for space)
"""

        /**
         * Time-position relationship
         */
        const val TIME_POSITION_RELATIONSHIP = """
GPS position and time are fundamentally linked through the speed of light.

CORE RELATIONSHIP:
- Position error (meters) = Time error (seconds) x 299,792,458 m/s
- 1 nanosecond timing error = 0.3 meters position error
- 1 microsecond timing error = 300 meters position error
- 1 millisecond timing error = 300 kilometers position error!

MEACONING ATTACK IMPLICATION:
- Replay attack delays real signal by some amount
- Delay causes position error proportional to delay
- To move target 1 km, need ~3 microsecond delay
- Time comparison with NTP can reveal meaconing

DETECTION APPROACH:
- Compare GPS time with NTP (network time)
- NTP accuracy ~10-50 ms, GPS accuracy ~100 ns
- If GPS time differs from NTP by > 1 second = suspicious
- Large time jump correlates with position jump = attack signature
"""

        /**
         * Galileo OSNMA explanation
         */
        const val GALILEO_OSNMA_EXPLANATION = """
OSNMA (Open Service Navigation Message Authentication) is Galileo's anti-spoofing feature.

HOW IT WORKS:
- Cryptographic signatures on navigation messages
- Uses TESLA protocol for broadcast authentication
- Keys released with delay (loose time synchronization required)
- Receiver can verify message authenticity after key release

OPERATIONAL STATUS:
- Initial service started August 2023
- Full operational capability expected 2024-2025
- Requires OSNMA-capable receiver
- Software update may enable existing receivers

LIMITATIONS:
- Requires ~10-15 minutes of data for initial authentication
- Loose time synchronization required (from other GNSS or NTP)
- Currently only Galileo supports OSNMA
- Not real-time (delayed authentication)

SPOOFING DETECTION:
- Authentication failure = CONFIRMED spoofing of Galileo signals
- Even if GPS is spoofed, Galileo can provide integrity
- Combined GPS + authenticated Galileo = strong defense
"""

        /**
         * Typical attack scenarios
         */
        val ATTACK_SCENARIO_DESCRIPTIONS = mapOf(
            "KREMLIN_CIRCLE" to """
Russian GPS Spoofing Pattern (documented since 2017):
- Location: Around Kremlin, Black Sea, Syria, Libya
- Effect: GPS shows wrong position (often at airport)
- Pattern: Circular spoofing zone centered on protected site
- Signature: GPS affected, GLONASS typically unaffected
- Purpose: Protect VIPs and sensitive facilities from GPS-guided threats
- Scale: Can affect ships, aircraft, phones within several km radius
""",
            "IRANIAN_MEACONING" to """
Iranian-Style GPS Capture (RQ-170 drone, 2011):
- Technique: Record real GPS signals, replay with modifications
- Effect: Gradual position drift toward desired location
- Signature: Steady drift in consistent direction
- Detection: Position drifting 5+ m/s consistently
- Purpose: Lead vehicle/aircraft to wrong destination
- Defense: INS cross-check, multi-constellation, velocity sanity check
""",
            "PERSONAL_JAMMER" to """
Personal Privacy Device (PPD) - Common illegal device:
- Purchase: Widely available online despite illegality
- Range: 5-50 meters typical, up to 500m for powerful units
- Effect: Complete GPS denial in affected area
- Users: Truckers avoiding tracking, criminals, privacy-concerned
- Detection: Sudden signal loss, recovery when source moves away
- Legality: Illegal in most countries (FCC fines up to $100K in US)
""",
            "POKEMON_SPOOFER" to """
Gaming/Location Spoofer (software-based):
- Technique: Mock location provider on device
- Effect: Fake location reported to apps
- Limitation: Does NOT affect raw GNSS measurements
- Detection: Raw measurements normal but Location API shows impossible position
- Purpose: Gaming (Pokemon GO), dating apps, employee tracking avoidance
- Note: This is LOCAL to the device, not a radio attack
"""
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

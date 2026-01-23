package com.flockyou.data.model

import java.util.UUID

/**
 * Database of known surveillance device signatures
 * Based on data from deflock.me, GainSec research, and FCC filings
 * 
 * Detection methodology:
 * - WiFi: SSID patterns, MAC OUI prefixes from LTE modems
 * - BLE: Device names, Service UUIDs (especially for Raven)
 * 
 * Flock cameras use cellular LTE modems (Quectel, Telit, Sierra Wireless)
 * and emit WiFi for configuration/management
 */
object DetectionPatterns {

    // ==================== CONSUMER TRACKER SPECIFICATIONS ====================
    // Comprehensive real-world knowledge about Bluetooth trackers for stalking detection

    /**
     * Detailed specifications and stalking-relevant information for consumer trackers.
     */
    data class TrackerSpecification(
        val manufacturerId: Int,
        val manufacturerName: String,
        val models: List<TrackerModel>,
        val antiStalkingFeatures: AntiStalkingFeatures,
        val confirmationMethods: List<String>,
        val physicalCharacteristics: PhysicalCharacteristics,
        val networkType: NetworkType,
        val stalkingRisk: StalkingRisk
    )

    data class TrackerModel(
        val name: String,
        val range: String,
        val soundLevel: String,
        val hasUwb: Boolean,
        val batteryType: String,
        val batteryLife: String,
        val dimensions: String,
        val weight: String
    )

    data class AntiStalkingFeatures(
        val alertsVictim: Boolean,
        val alertPlatform: String,
        val playsSoundAutomatically: Boolean,
        val soundDelayHours: IntRange?,
        val canBeScannedByOtherApps: Boolean,
        val ownerInfoAccessible: Boolean,
        val ownerInfoMethod: String?
    )

    data class PhysicalCharacteristics(
        val shape: String,
        val commonHidingSpots: List<String>,
        val visualIdentifiers: String,
        val nfcCapable: Boolean
    )

    enum class NetworkType {
        APPLE_FIND_MY, TILE_NETWORK, SAMSUNG_SMARTTHINGS, STANDALONE_BLE, IBEACON_COMPATIBLE
    }

    enum class StalkingRisk(val level: Int, val description: String) {
        CRITICAL(5, "Frequently used for stalking, hard to detect"),
        HIGH(4, "Often misused, moderate detection difficulty"),
        MEDIUM(3, "Can be misused, but has anti-stalking features"),
        LOW(2, "Rarely used for stalking, easy to detect"),
        MINIMAL(1, "Designed with anti-stalking as priority")
    }

    val trackerSpecifications = mapOf(
        DeviceType.AIRTAG to TrackerSpecification(
            manufacturerId = 0x004C, manufacturerName = "Apple",
            models = listOf(TrackerModel("AirTag", "30ft BLE + UWB Precision Finding", "60dB", true, "CR2032", "~1 year", "31.9mm x 8mm", "11g")),
            antiStalkingFeatures = AntiStalkingFeatures(true, "iOS (auto), Android (Tracker Detect app)", true, 8..24, true, true, "NFC tap shows partial phone number and serial"),
            confirmationMethods = listOf("Use Apple 'Tracker Detect' app (free on Android)", "NFC tap AirTag to see owner info and serial", "Wait for automatic sound (8-24 hours)", "iPhone: 'AirTag Found Moving With You' notification", "Search: bags, car wheel wells, jacket pockets, phone cases", "iPhone 11+: Use Precision Finding"),
            physicalCharacteristics = PhysicalCharacteristics("Circular disc, white/silver", listOf("Car wheel wells", "Bag pockets/lining", "Jacket pockets", "Phone cases", "Keychains", "Shoes", "OBD-II port area", "Under car seats", "Luggage"), "Apple logo, silver back, quarter-sized", true),
            networkType = NetworkType.APPLE_FIND_MY, stalkingRisk = StalkingRisk.HIGH
        ),
        DeviceType.TILE_TRACKER to TrackerSpecification(
            manufacturerId = 0x00C7, manufacturerName = "Tile (Life360)",
            models = listOf(
                TrackerModel("Tile Pro", "400ft", "Loudest", false, "CR2032", "~1 year", "42x42x6.5mm", "12g"),
                TrackerModel("Tile Mate", "250ft", "Medium", false, "CR1632", "~3 years", "38x38x7.2mm", "7.5g"),
                TrackerModel("Tile Slim", "250ft", "Medium", false, "Non-replaceable", "~3 years", "86x54x2.5mm (credit card)", "14g"),
                TrackerModel("Tile Sticker", "150ft", "Quietest", false, "Non-replaceable", "~3 years", "27mm x 7.3mm", "5g")
            ),
            antiStalkingFeatures = AntiStalkingFeatures(false, "None (opt-in Scan and Secure only)", false, null, true, false, "No owner info - must contact Tile/police"),
            confirmationMethods = listOf("Use Tile 'Scan and Secure' feature (opt-in)", "Tiles do NOT auto-alert like AirTags", "Press Tile button 3x to make it ring", "Tile Slim is credit-card sized - check wallets", "No NFC - cannot tap to identify"),
            physicalCharacteristics = PhysicalCharacteristics("Square/Card/Circular", listOf("Wallets (Slim)", "Key rings", "Bag pockets", "Stuck to objects (Sticker)", "Car interior", "Coat linings"), "Tile 'T' logo, white/black, button on side", false),
            networkType = NetworkType.TILE_NETWORK, stalkingRisk = StalkingRisk.CRITICAL
        ),
        DeviceType.SAMSUNG_SMARTTAG to TrackerSpecification(
            manufacturerId = 0x0075, manufacturerName = "Samsung",
            models = listOf(
                TrackerModel("SmartTag", "390ft", "89dB", false, "CR2032", "~300 days", "39x39x9.9mm", "13g"),
                TrackerModel("SmartTag+", "390ft + UWB", "89dB", true, "CR2032", "~165 days", "39x39x9.9mm", "13g"),
                TrackerModel("SmartTag2", "390ft", "Medium", false, "CR2032", "~500 days", "45x45x9mm", "14.5g")
            ),
            antiStalkingFeatures = AntiStalkingFeatures(true, "Samsung Galaxy with SmartThings", true, 8..24, true, false, "Samsung provides to law enforcement with warrant"),
            confirmationMethods = listOf("Galaxy: 'Unknown Tag Detected' auto-alerts", "Use SmartThings app to scan", "Non-Galaxy: 'SmartThings Find' app", "Press button to ring", "SmartTag+ AR finder on Galaxy"),
            physicalCharacteristics = PhysicalCharacteristics("Rounded square with keyring hole", listOf("Keychains", "Bags/pockets", "Car interior", "Pet collars", "Luggage"), "Samsung logo, button on front", false),
            networkType = NetworkType.SAMSUNG_SMARTTHINGS, stalkingRisk = StalkingRisk.MEDIUM
        ),
        DeviceType.GENERIC_BLE_TRACKER to TrackerSpecification(
            manufacturerId = 0x0000, manufacturerName = "Various (Chipolo, Eufy, Pebblebee, etc.)",
            models = listOf(
                TrackerModel("Chipolo ONE Spot", "200ft + Find My", "120dB (loudest)", false, "CR2032", "~2 years", "37.9x6.4mm", "8g"),
                TrackerModel("Eufy SmartTrack Link", "262ft + Find My", "Moderate", false, "CR2032", "~1 year", "37x37x6.5mm", "10g"),
                TrackerModel("Pebblebee Clip/Card", "500ft", "Moderate", false, "USB rechargeable", "~6 months", "Varies", "~10g"),
                TrackerModel("AliExpress Generic", "100-200ft", "Usually quiet", false, "CR2032", "6-12 months", "Varies", "5-15g")
            ),
            antiStalkingFeatures = AntiStalkingFeatures(true, "iOS (Find My compatible)", true, 8..24, true, false, "Contact manufacturer or law enforcement"),
            confirmationMethods = listOf("Find My compatible: iPhone alerts", "Use manufacturer's app", "Press button to ring (if present)", "Generic AliExpress trackers often have NO anti-stalking"),
            physicalCharacteristics = PhysicalCharacteristics("Varies: circular, square, card", listOf("Bags, car, clothes", "Pet collars", "Wallets (card type)"), "Brand logo, plastic, button for ring", false),
            networkType = NetworkType.APPLE_FIND_MY, stalkingRisk = StalkingRisk.HIGH
        )
    )

    // ==================== STALKING DETECTION HEURISTICS ====================

    data class StalkingHeuristic(val name: String, val condition: String, val suspicionLevel: SuspicionLevel, val interpretation: String, val actionRequired: String)

    enum class SuspicionLevel(val score: Int, val color: String) {
        CRITICAL(100, "RED"), HIGH(75, "ORANGE"), MEDIUM(50, "YELLOW"), LOW(25, "BLUE"), MINIMAL(10, "GREEN")
    }

    val stalkingHeuristics = listOf(
        StalkingHeuristic("Multiple Locations", "Same tracker at 3+ distinct locations", SuspicionLevel.CRITICAL, "Tracker is FOLLOWING you.", "Document and contact authorities."),
        StalkingHeuristic("Extended Presence", "Same tracker 30+ min while moving", SuspicionLevel.HIGH, "Tracker moving with you, hidden in belongings.", "Search belongings and vehicle."),
        StalkingHeuristic("Possession Signal", "Strong signal (-40 to -60 dBm) with low variance", SuspicionLevel.CRITICAL, "Tracker ON YOUR PERSON.", "Check pockets, bags, shoes immediately."),
        StalkingHeuristic("Home Departure", "Tracker appears when leaving home", SuspicionLevel.CRITICAL, "Planted at home or on vehicle.", "Check vehicle. Consider home security."),
        StalkingHeuristic("Person Correlation", "Disappears with specific person", SuspicionLevel.CRITICAL, "That person owns/planted it.", "Document pattern. May be domestic."),
        StalkingHeuristic("Work Hours Only", "Only appears during work hours", SuspicionLevel.HIGH, "Planted at workplace.", "Search work bag, laptop case, jacket."),
        StalkingHeuristic("Location-Triggered", "Appears after visiting a location", SuspicionLevel.HIGH, "Planted at that location.", "Think about when it first appeared."),
        StalkingHeuristic("Weak Fluctuating", "Weak signal with high variance", SuspicionLevel.MINIMAL, "Passing tracker, not targeting you.", "Monitor but likely safe.")
    )

    // ==================== STALKING RESPONSE GUIDANCE ====================

    object StalkingResponseGuidance {
        val immediateActions = listOf(
            "1. DOCUMENT - Screenshots with timestamps/locations",
            "2. DO NOT DESTROY - It's evidence. Removing battery is OK.",
            "3. If in danger, call 911",
            "4. Faraday bag/metal container stops transmission",
            "5. Note who had access to your belongings/vehicle/home"
        )

        val supportResources = mapOf(
            "National Domestic Violence Hotline" to "1-800-799-7233 (24/7)",
            "SPARC (Stalking Prevention)" to "stalkingawareness.org",
            "Cyber Civil Rights Initiative" to "cybercivilrights.org",
            "Tech Safety (NNEDV)" to "techsafety.org"
        )

        val whatNotToDo = listOf(
            "DO NOT confront stalker directly - can escalate",
            "DO NOT destroy tracker before documenting",
            "DO NOT ignore repeated detections",
            "DO NOT post on social media (alerts stalker)"
        )

        fun getGuidanceForSuspicionLevel(score: Int): String = when {
            score >= 80 -> "CRITICAL: Call 911 if danger. Document now. Hotline: 1-800-799-7233. DO NOT destroy tracker."
            score >= 60 -> "HIGH: Search belongings/vehicle/clothes. Document all. Consider police non-emergency line."
            score >= 40 -> "MODERATE: Monitor across locations. Casual search of items. Continue documenting."
            else -> "LOW: Likely passing tracker. Keep scanning to see if it reappears."
        }
    }

    // ==================== SURVEILLANCE EQUIPMENT CONTEXT ====================

    object SurveillanceEquipmentContext {
        val axonSignalInfo = mapOf(
            "description" to "Axon Signal Sidearm triggers body cameras when weapon drawn. ~1 pps normal, 20-50 pps activated.",
            "triggers" to listOf("Weapon unholstered", "TASER armed", "Siren activated", "Vehicle crash", "Manual button"),
            "what_it_means" to "Police engagement in progress. Multiple body cameras recording. You may be on video."
        )

        val ravenInfo = mapOf(
            "description" to "Flock Safety acoustic surveillance. Listens for gunfire AND 'human distress' (screaming). Solar, 24/7.",
            "vulnerability" to "GainSec research: leaks GPS, battery, network info, detection counts via BLE.",
            "concerns" to listOf("Continuous audio surveillance", "Vague 'distress' definition", "No warrant needed", "False positives trigger police")
        )
    }

    // ==================== SMART HOME PRIVACY CONTEXT ====================

    data class SmartHomeProfile(val manufacturer: String, val lawEnforcementSharing: Boolean, val details: String, val retention: String, val recommendations: List<String>)

    val smartHomeProfiles = mapOf(
        DeviceType.RING_DOORBELL to SmartHomeProfile("Ring (Amazon)", true, "2,500+ police partnerships. Can request footage without user consent.", "60 days", listOf("Disable Neighbors app", "Minimize cloud storage")),
        DeviceType.NEST_CAMERA to SmartHomeProfile("Google/Nest", true, "Can share via legal process. Always-on microphones.", "30 days (paid)", listOf("Review Google Activity", "Disable Familiar face")),
        DeviceType.EUFY_CAMERA to SmartHomeProfile("Eufy/Anker", false, "Claims 'local only' but caught sending thumbnails to cloud (2022).", "Local", listOf("Be skeptical of claims", "Monitor network traffic")),
        DeviceType.BLINK_CAMERA to SmartHomeProfile("Blink (Amazon)", true, "Same as Ring - Amazon ownership.", "60 days", listOf("Use local Sync Module", "Same Ring concerns"))
    )

    // ==================== MAC RANDOMIZATION CONTEXT ====================

    object MacRandomizationContext {
        val explanation = "Modern trackers rotate MACs (~15 min) but are identified by payload, manufacturer data, service UUIDs, and timing."
        val intervals = mapOf("AirTag" to "~15 min", "Tile" to "~10-15 min", "SmartTag" to "~15 min", "Generic" to "Varies")
    }

    // ==================== SSID Patterns ====================
    // Primary detection method - Flock cameras advertise specific SSIDs
    val ssidPatterns = listOf(
        // Flock Safety - Primary patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^flock[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety ALPR Camera - captures license plates and vehicle characteristics",
            sourceUrl = "https://www.eff.org/deeplinks/2024/03/how-flock-safety-cameras-can-be-used-track-your-car"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^fs[_-].*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Safety Camera (FS prefix variant)",
            sourceUrl = "https://www.flocksafety.com/products/flock-safety-cameras"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^falcon[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Falcon ALPR - standard pole-mounted camera",
            sourceUrl = "https://www.flocksafety.com/products/falcon"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^sparrow[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Sparrow ALPR - compact camera model",
            sourceUrl = "https://www.flocksafety.com/products/sparrow"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^condor[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Condor ALPR - high-speed multi-lane camera",
            sourceUrl = "https://www.flocksafety.com/products/condor"
        ),
        
        // Penguin surveillance
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^penguin[_-]?.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin Surveillance Device - mobile ALPR system"
        ),
        
        // Pigvision
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^pigvision[_-]?.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision Surveillance System"
        ),
        
        // Generic ALPR patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^alpr[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 80,
            description = "Generic ALPR System - Automated License Plate Reader"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^lpr[_-]?cam.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 75,
            description = "License Plate Reader Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^vigilant[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Motorola Solutions",
            threatScore = 85,
            description = "Vigilant ALPR (Motorola) - competitor to Flock"
        ),
        
        // ==================== Police Technology Patterns ====================
        
        // Motorola Solutions
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^moto[_-]?(body|cam|radio|apx).*",
            deviceType = DeviceType.MOTOROLA_POLICE_TECH,
            manufacturer = "Motorola Solutions",
            threatScore = 80,
            description = "Motorola police equipment (body camera, radio, APX)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^apx[_-]?.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "Motorola Solutions",
            threatScore = 75,
            description = "Motorola APX Radio System"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^astro[_-]?.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "Motorola Solutions",
            threatScore = 70,
            description = "Motorola ASTRO Radio System"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^v[_-]?[35]00[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions",
            threatScore = 80,
            description = "Motorola V300/V500 Body Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^watchguard[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions (WatchGuard)",
            threatScore = 80,
            description = "WatchGuard Body/Dash Camera System"
        ),
        
        // Axon (formerly TASER)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^axon[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 85,
            description = "Axon police equipment (body camera, TASER, etc.)",
            sourceUrl = "https://www.axon.com/products"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(axon[_-]?)?(body|flex)[_-]?[234]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon Body Camera (Body 2/3/4, Flex)",
            sourceUrl = "https://www.axon.com/products/body-cameras"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^taser[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 75,
            description = "TASER device with connectivity"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^evidence[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 70,
            description = "Axon Evidence.com sync device"
        ),
        
        // L3Harris
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^l3harris[_-]?.*",
            deviceType = DeviceType.L3HARRIS_SURVEILLANCE,
            manufacturer = "L3Harris Technologies",
            threatScore = 85,
            description = "L3Harris surveillance/communications equipment"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^stingray[_-]?.*",
            deviceType = DeviceType.STINGRAY_IMSI,
            manufacturer = "L3Harris Technologies",
            threatScore = 100,
            description = "StingRay Cell Site Simulator (IMSI Catcher)",
            sourceUrl = "https://www.eff.org/pages/cell-site-simulatorsimsi-catchers"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(hail|king|queen)storm[_-]?.*",
            deviceType = DeviceType.STINGRAY_IMSI,
            manufacturer = "L3Harris Technologies",
            threatScore = 100,
            description = "Hailstorm/Kingfish Cell Site Simulator",
            sourceUrl = "https://www.aclu.org/issues/privacy-technology/surveillance-technologies/stingray-tracking-devices"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(harris|xg)[_-]?[0-9]+.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "L3Harris Technologies",
            threatScore = 70,
            description = "Harris XG Radio System"
        ),
        
        // Digital Ally
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^digital[_-]?ally[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Digital Ally",
            threatScore = 75,
            description = "Digital Ally Body/Dash Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^firstvu[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Digital Ally",
            threatScore = 75,
            description = "Digital Ally FirstVU Body Camera"
        ),
        
        // ==================== Mobile Forensics / Phone Extraction Devices ====================
        // CRITICAL: Detection of these devices near you may indicate device seizure risk

        // Cellebrite UFED (Universal Forensic Extraction Device)
        // $15,000-$30,000+ per unit, used by police, border agents, military
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^cellebrite[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 95,
            description = "Cellebrite mobile forensics - can extract ALL data from phones including deleted content",
            sourceUrl = "https://www.eff.org/pages/cellebrite"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^ufed[_-]?(touch|4pc|ultimate|premium)?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 95,
            description = "Cellebrite UFED - extracts messages, photos, app data, passwords from locked phones",
            sourceUrl = "https://cellebrite.com/en/ufed/"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(physical|logical)[_-]?analyzer.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 90,
            description = "Cellebrite Physical/Logical Analyzer - forensic data analysis tool"
        ),

        // GrayKey (Grayshift) - specifically designed to crack iPhones
        // $15,000-$30,000 per unit, law enforcement only
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^graykey[_-]?.*",
            deviceType = DeviceType.GRAYKEY_DEVICE,
            manufacturer = "Grayshift",
            threatScore = 95,
            description = "GrayKey iPhone forensics - can bypass iPhone passcodes and extract data",
            sourceUrl = "https://www.vice.com/en/article/graykey-iphone-unlocker-goes-on-sale-to-cops/"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^grayshift[_-]?.*",
            deviceType = DeviceType.GRAYKEY_DEVICE,
            manufacturer = "Grayshift",
            threatScore = 95,
            description = "Grayshift forensics device"
        ),

        // Magnet Forensics (cloud and device forensics)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^magnet[_-]?(forensic|axiom|acquire).*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Magnet Forensics",
            threatScore = 90,
            description = "Magnet Forensics - cloud and device data extraction"
        ),

        // MSAB XRY (Swedish mobile forensics)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(msab|xry)[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "MSAB",
            threatScore = 90,
            description = "MSAB XRY mobile forensics system"
        ),

        // Oxygen Forensics
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^oxygen[_-]?forensic.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Oxygen Forensics",
            threatScore = 85,
            description = "Oxygen Forensic Detective - mobile data extraction"
        ),

        // Generic forensics patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(mobile|phone|device)[_-]?forensic.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = null,
            threatScore = 85,
            description = "Mobile forensics device - may extract data from phones"
        ),
        
        // Genetec
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^genetec[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Genetec",
            threatScore = 80,
            description = "Genetec Security Center / AutoVu ALPR"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^autovu[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Genetec",
            threatScore = 85,
            description = "Genetec AutoVu ALPR System"
        ),
        
        // Getac (ruggedized police computers)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^getac[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Getac",
            threatScore = 60,
            description = "Getac ruggedized computer (often used in patrol vehicles)"
        ),
        
        // Panasonic Toughbook (common in police vehicles)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^toughbook[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Panasonic",
            threatScore = 55,
            description = "Panasonic Toughbook (commonly used by law enforcement)"
        ),

        // ==================== Smart Home / IoT Surveillance Patterns ====================

        // Ring Doorbells
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^ring[_-]?(doorbell|cam|setup|stick).*",
            deviceType = DeviceType.RING_DOORBELL,
            manufacturer = "Ring (Amazon)",
            threatScore = 40,
            description = "Ring doorbell/camera - shares footage with 2,500+ law enforcement agencies"
        ),

        // Nest/Google Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(nest|google)[_-]?(cam|doorbell|hello).*",
            deviceType = DeviceType.NEST_CAMERA,
            manufacturer = "Google/Nest",
            threatScore = 35,
            description = "Nest/Google camera - cloud-connected home surveillance"
        ),

        // Amazon Sidewalk
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(amazon[_-]?sidewalk|sidewalk[_-]?bridge).*",
            deviceType = DeviceType.AMAZON_SIDEWALK,
            manufacturer = "Amazon",
            threatScore = 45,
            description = "Amazon Sidewalk mesh network - shares bandwidth with neighbors/Amazon"
        ),

        // Wyze Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^wyze[_-]?(cam|doorbell|setup).*",
            deviceType = DeviceType.WYZE_CAMERA,
            manufacturer = "Wyze",
            threatScore = 35,
            description = "Wyze camera - budget smart home camera"
        ),

        // Arlo Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^arlo[_-]?(cam|pro|ultra|setup).*",
            deviceType = DeviceType.ARLO_CAMERA,
            manufacturer = "Arlo",
            threatScore = 35,
            description = "Arlo security camera"
        ),

        // Eufy/Anker Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^eufy[_-]?(cam|doorbell|security).*",
            deviceType = DeviceType.EUFY_CAMERA,
            manufacturer = "Eufy/Anker",
            threatScore = 35,
            description = "Eufy security camera"
        ),

        // Blink Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^blink[_-]?(cam|mini|setup).*",
            deviceType = DeviceType.BLINK_CAMERA,
            manufacturer = "Blink (Amazon)",
            threatScore = 40,
            description = "Blink camera (Amazon) - cloud-connected surveillance"
        ),

        // SimpliSafe
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^simplisafe[_-]?.*",
            deviceType = DeviceType.SIMPLISAFE_DEVICE,
            manufacturer = "SimpliSafe",
            threatScore = 35,
            description = "SimpliSafe security system"
        ),

        // ADT Security
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^adt[_-]?(pulse|cam|security).*",
            deviceType = DeviceType.ADT_DEVICE,
            manufacturer = "ADT",
            threatScore = 40,
            description = "ADT security system - may share with law enforcement"
        ),

        // Vivint
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^vivint[_-]?.*",
            deviceType = DeviceType.VIVINT_DEVICE,
            manufacturer = "Vivint",
            threatScore = 40,
            description = "Vivint smart home security"
        ),

        // ==================== Traffic Enforcement Patterns ====================

        // Speed Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(speed[_-]?cam|redflex|verra|xerox[_-]?ats).*",
            deviceType = DeviceType.SPEED_CAMERA,
            manufacturer = null,
            threatScore = 70,
            description = "Speed enforcement camera"
        ),

        // Red Light Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(red[_-]?light|intersection[_-]?cam|ats[_-]?).*",
            deviceType = DeviceType.RED_LIGHT_CAMERA,
            manufacturer = null,
            threatScore = 65,
            description = "Red light enforcement camera"
        ),

        // Toll Systems
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(ezpass|sunpass|fastrak|toll[_-]?gantry).*",
            deviceType = DeviceType.TOLL_READER,
            manufacturer = null,
            threatScore = 50,
            description = "Electronic toll collection system"
        ),

        // ==================== Network Attack/Pentest Device Patterns ====================

        // WiFi Pineapple
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(pineapple|hak5|wifi[_-]?pineapple).*",
            deviceType = DeviceType.WIFI_PINEAPPLE,
            manufacturer = "Hak5",
            threatScore = 90,
            description = "WiFi Pineapple - network auditing/attack tool"
        ),

        // ==================== Retail/Commercial WiFi Tracking Patterns ====================
        // These systems track customers via WiFi probe requests and MAC addresses

        // Major retail analytics providers
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(retailnext|shoppertrak|footfall).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = null,
            threatScore = 50,
            description = "Retail foot traffic analytics - tracks customer movement via WiFi"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^euclid[_-]?(analytics|element).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = "Euclid Analytics",
            threatScore = 55,
            description = "Euclid Analytics - retail WiFi tracking and analytics"
        ),

        // Cisco Meraki WiFi analytics (very common in retail/enterprise)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^meraki[_-]?(analytics|presence|scanning).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = "Cisco Meraki",
            threatScore = 45,
            description = "Cisco Meraki WiFi analytics - location and presence tracking"
        ),

        // Aruba/HPE (common in enterprise, can track devices)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^aruba[_-]?(analytics|meridian|location).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = "Aruba (HPE)",
            threatScore = 45,
            description = "Aruba WiFi analytics - enterprise location tracking"
        ),

        // Mist Systems (Juniper) - AI-driven analytics
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^mist[_-]?(ai|analytics).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = "Mist (Juniper)",
            threatScore = 45,
            description = "Mist AI analytics - machine learning WiFi tracking"
        ),

        // Generic WiFi analytics patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(wifi|wlan)[_-]?(analytics|tracking|presence).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = null,
            threatScore = 50,
            description = "WiFi analytics system - may track device presence and movement"
        ),

        // ==================== Hidden Camera WiFi Patterns ====================
        // Common SSIDs from cheap IP cameras often used for covert surveillance

        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(ipc|ipcam|ip[_-]?cam(era)?)[_-]?[0-9a-f]*$",
            deviceType = DeviceType.HIDDEN_CAMERA,
            manufacturer = null,
            threatScore = 70,
            description = "IP Camera default SSID - common in hidden cameras"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(wifi[_-]?cam|wificam)[_-]?[0-9a-f]*$",
            deviceType = DeviceType.HIDDEN_CAMERA,
            manufacturer = null,
            threatScore = 70,
            description = "WiFi Camera default SSID"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^p2p[_-]?[0-9a-f]+$",
            deviceType = DeviceType.HIDDEN_CAMERA,
            manufacturer = null,
            threatScore = 65,
            description = "P2P Camera protocol SSID"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(spy|nanny|hidden|covert|pinhole)[_-]?cam.*",
            deviceType = DeviceType.HIDDEN_CAMERA,
            manufacturer = null,
            threatScore = 85,
            description = "Explicitly named hidden/spy camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(clock|smoke|outlet|charger|usb)[_-]?cam.*",
            deviceType = DeviceType.HIDDEN_CAMERA,
            manufacturer = null,
            threatScore = 80,
            description = "Disguised camera (clock, smoke detector, USB charger, etc.)"
        )
    )
    
    // ==================== BLE Device Name Patterns ====================
    val bleNamePatterns = listOf(
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^flock[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety BLE Configuration Interface"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^falcon[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Falcon Camera BLE"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^raven[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "Flock Safety / SoundThinking",
            threatScore = 100,
            description = "Raven Acoustic Gunshot Detector - listens for gunfire and 'human distress'"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^shotspotter[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking (formerly ShotSpotter)",
            threatScore = 100,
            description = "ShotSpotter Acoustic Sensor - gunfire detection system"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^soundthinking[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking",
            threatScore = 100,
            description = "SoundThinking Acoustic Surveillance Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^penguin[_-]?.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin BLE Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^pigvision[_-]?.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision BLE Device"
        ),
        
        // ==================== Police Technology BLE Patterns ====================
        
        // Axon Body Cameras
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^axon[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon device (body camera, TASER, etc.)"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(body|flex)[_-]?[234]?[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon Body Camera"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^ab[234][_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon Body Camera (AB2/AB3/AB4)"
        ),
        
        // Motorola Body Cameras
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(moto|si)[_-]?[v][_-]?[0-9]+.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions",
            threatScore = 80,
            description = "Motorola Body Camera"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^watchguard[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions (WatchGuard)",
            threatScore = 80,
            description = "WatchGuard Body/Dash Camera"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^apx[_-]?.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "Motorola Solutions",
            threatScore = 70,
            description = "Motorola APX Radio"
        ),
        
        // Digital Ally
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(da|firstvu)[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Digital Ally",
            threatScore = 75,
            description = "Digital Ally Body Camera"
        ),
        
        // L3Harris
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(l3|harris|l3harris)[_-]?.*",
            deviceType = DeviceType.L3HARRIS_SURVEILLANCE,
            manufacturer = "L3Harris Technologies",
            threatScore = 80,
            description = "L3Harris equipment"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^xg[_-]?[0-9]+.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "L3Harris Technologies",
            threatScore = 70,
            description = "L3Harris XG Radio"
        ),
        
        // Cellebrite / Forensics
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(cellebrite|ufed)[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 95,
            description = "Cellebrite forensics device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^graykey[_-]?.*",
            deviceType = DeviceType.GRAYKEY_DEVICE,
            manufacturer = "Grayshift",
            threatScore = 95,
            description = "GrayKey forensics device"
        ),

        // ==================== Whelen Lightbar / Emergency Vehicle Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^cencom[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 90,
            description = "Whelen CenCom Lightbar Controller - police/emergency vehicle lightbar sync"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^wecan[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 90,
            description = "Whelen WeCAN Network - vehicle lighting controller"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^whelen[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 85,
            description = "Whelen emergency lighting equipment"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^whelen[_-]?core[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 80,
            description = "Whelen Core lightbar system"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^whelen[_-]?(ion|legacy|liberty|freedom)[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 75,
            description = "Whelen lightbar series"
        ),

        // ==================== Axon Signal / Body Camera Trigger Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^signal[_-]?(sidearm|vehicle|performance)?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 90,
            description = "Axon Signal - auto-activates body cameras on siren/gun draw"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^fleet[_-]?[23]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 85,
            description = "Axon Fleet in-car camera system"
        ),

        // ==================== Federal Signal Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^federal[_-]?signal.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Federal Signal",
            threatScore = 85,
            description = "Federal Signal emergency lighting"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(valor|integrity|allegiant)[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Federal Signal",
            threatScore = 80,
            description = "Federal Signal lightbar"
        ),

        // ==================== Code 3 / SoundOff Signal Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^code[_-]?3.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Code 3",
            threatScore = 80,
            description = "Code 3 emergency lighting"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^soundoff[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "SoundOff Signal",
            threatScore = 80,
            description = "SoundOff Signal emergency equipment"
        ),

        // ==================== Tracker/AirTag Detection Patterns ====================

        // Apple AirTag
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(airtag|find[_-]?my).*",
            deviceType = DeviceType.AIRTAG,
            manufacturer = "Apple",
            threatScore = 60,
            description = "Apple AirTag - potential tracking device"
        ),

        // Tile Trackers
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^tile[_-]?(mate|pro|slim|sticker)?.*",
            deviceType = DeviceType.TILE_TRACKER,
            manufacturer = "Tile",
            threatScore = 55,
            description = "Tile Bluetooth tracker"
        ),

        // Samsung SmartTag
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(smart[_-]?tag|galaxy[_-]?tag).*",
            deviceType = DeviceType.SAMSUNG_SMARTTAG,
            manufacturer = "Samsung",
            threatScore = 55,
            description = "Samsung SmartTag tracker"
        ),

        // Generic BLE Trackers
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(chipolo|nut[_-]?find|pebblebee|cube[_-]?tracker).*",
            deviceType = DeviceType.GENERIC_BLE_TRACKER,
            manufacturer = null,
            threatScore = 50,
            description = "Generic Bluetooth tracker device"
        ),

        // ==================== Smart Home IoT BLE Patterns ====================

        // Ring
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^ring[_-]?(doorbell|cam|chime|setup).*",
            deviceType = DeviceType.RING_DOORBELL,
            manufacturer = "Ring (Amazon)",
            threatScore = 40,
            description = "Ring doorbell/camera BLE setup"
        ),

        // Nest/Google
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(nest|google)[_-]?(cam|doorbell|hello|hub).*",
            deviceType = DeviceType.NEST_CAMERA,
            manufacturer = "Google/Nest",
            threatScore = 35,
            description = "Google Nest camera/doorbell"
        ),

        // Wyze
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^wyze[_-]?.*",
            deviceType = DeviceType.WYZE_CAMERA,
            manufacturer = "Wyze",
            threatScore = 35,
            description = "Wyze smart home device"
        ),

        // Arlo
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^arlo[_-]?.*",
            deviceType = DeviceType.ARLO_CAMERA,
            manufacturer = "Arlo",
            threatScore = 35,
            description = "Arlo security camera"
        ),

        // Eufy
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^eufy[_-]?.*",
            deviceType = DeviceType.EUFY_CAMERA,
            manufacturer = "Eufy/Anker",
            threatScore = 35,
            description = "Eufy security device"
        ),

        // Blink
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^blink[_-]?.*",
            deviceType = DeviceType.BLINK_CAMERA,
            manufacturer = "Blink (Amazon)",
            threatScore = 40,
            description = "Blink camera"
        ),

        // ==================== Retail Beacon Patterns ====================

        // iBeacon / Eddystone patterns
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(ibeacon|eddystone|estimote|kontakt).*",
            deviceType = DeviceType.BLUETOOTH_BEACON,
            manufacturer = null,
            threatScore = 45,
            description = "Retail/location Bluetooth beacon"
        ),

        // Retail analytics
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(retailnext|shoppertrak|sensoro).*",
            deviceType = DeviceType.RETAIL_TRACKER,
            manufacturer = null,
            threatScore = 50,
            description = "Retail foot traffic sensor"
        ),

        // ==================== Law Enforcement Specific BLE Patterns ====================

        // ShotSpotter/Acoustic
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(shotspot|soundthink|acoustic[_-]?sens).*",
            deviceType = DeviceType.SHOTSPOTTER,
            manufacturer = "SoundThinking",
            threatScore = 95,
            description = "ShotSpotter acoustic gunshot sensor"
        ),

        // GrayKey
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^graykey.*",
            deviceType = DeviceType.GRAYKEY_DEVICE,
            manufacturer = "Grayshift",
            threatScore = 95,
            description = "GrayKey mobile forensics device"
        ),

        // ==================== Flipper Zero and Hacking Tool Patterns ====================

        // Flipper Zero - Default and common device names
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^flipper[_\\- ]?(zero)?[_\\- ]?.*",
            deviceType = DeviceType.FLIPPER_ZERO,
            manufacturer = "Flipper Devices",
            threatScore = 65,
            description = "Flipper Zero multi-tool hacking device - Sub-GHz, RFID, NFC, IR, BLE capable"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^flip[_\\- ]?[0-9a-f]+.*",
            deviceType = DeviceType.FLIPPER_ZERO,
            manufacturer = "Flipper Devices",
            threatScore = 60,
            description = "Flipper Zero (serial number format)"
        ),
        // Flipper custom firmware naming patterns
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(unleashed|roguemaster|xtreme|momentum)[_\\- ]?.*",
            deviceType = DeviceType.FLIPPER_ZERO,
            manufacturer = "Flipper Devices (Custom FW)",
            threatScore = 75,
            description = "Flipper Zero with custom firmware (Unleashed/RogueMaster/Xtreme) - enhanced capabilities"
        ),
        // Flipper BadUSB/BLE mode patterns
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^badusb[_\\- ]?.*",
            deviceType = DeviceType.FLIPPER_ZERO,
            manufacturer = "Flipper Devices",
            threatScore = 85,
            description = "Flipper Zero in BadUSB mode - keystroke injection capable"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^badbt[_\\- ]?.*",
            deviceType = DeviceType.FLIPPER_ZERO,
            manufacturer = "Flipper Devices",
            threatScore = 85,
            description = "Flipper Zero in BadBT mode - Bluetooth keystroke injection"
        ),

        // Hak5 Devices
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(bash[_\\- ]?bunny|bashbunny).*",
            deviceType = DeviceType.BASH_BUNNY,
            manufacturer = "Hak5",
            threatScore = 80,
            description = "Hak5 Bash Bunny - USB attack platform"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(lan[_\\- ]?turtle|lanturtle).*",
            deviceType = DeviceType.LAN_TURTLE,
            manufacturer = "Hak5",
            threatScore = 80,
            description = "Hak5 LAN Turtle - covert network access device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(rubber[_\\- ]?ducky|rubberducky).*",
            deviceType = DeviceType.USB_RUBBER_DUCKY,
            manufacturer = "Hak5",
            threatScore = 75,
            description = "Hak5 USB Rubber Ducky - keystroke injection device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(key[_\\- ]?croc|keycroc).*",
            deviceType = DeviceType.KEYCROC,
            manufacturer = "Hak5",
            threatScore = 85,
            description = "Hak5 Key Croc - keylogger with WiFi exfiltration"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(shark[_\\- ]?jack|sharkjack).*",
            deviceType = DeviceType.SHARK_JACK,
            manufacturer = "Hak5",
            threatScore = 80,
            description = "Hak5 Shark Jack - portable network attack tool"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(screen[_\\- ]?crab|screencrab).*",
            deviceType = DeviceType.SCREEN_CRAB,
            manufacturer = "Hak5",
            threatScore = 85,
            description = "Hak5 Screen Crab - HDMI man-in-the-middle"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^hak5[_\\- ]?.*",
            deviceType = DeviceType.GENERIC_HACKING_TOOL,
            manufacturer = "Hak5",
            threatScore = 75,
            description = "Hak5 security testing device"
        ),

        // SDR/RF Tools
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(hackrf|portapack).*",
            deviceType = DeviceType.HACKRF_SDR,
            manufacturer = "Great Scott Gadgets",
            threatScore = 70,
            description = "HackRF/PortaPack SDR - RF analysis and transmission capable"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(sdr|rtl[_\\- ]?sdr).*",
            deviceType = DeviceType.HACKRF_SDR,
            manufacturer = null,
            threatScore = 50,
            description = "Software Defined Radio device - RF monitoring capable"
        ),

        // RFID/NFC Tools
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^proxmark.*",
            deviceType = DeviceType.PROXMARK,
            manufacturer = "Proxmark",
            threatScore = 80,
            description = "Proxmark RFID/NFC tool - can clone access cards"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(chameleon|chameleomini).*",
            deviceType = DeviceType.PROXMARK,
            manufacturer = null,
            threatScore = 75,
            description = "ChameleonMini RFID emulator - card cloning device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(icopy|icopy[_\\- ]?x).*",
            deviceType = DeviceType.PROXMARK,
            manufacturer = null,
            threatScore = 70,
            description = "iCopy-X RFID cloner"
        ),

        // Generic hacking/pentest patterns
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(pentest|hackbox|pwn|0wn|hack[_\\- ]?tool).*",
            deviceType = DeviceType.GENERIC_HACKING_TOOL,
            manufacturer = null,
            threatScore = 65,
            description = "Potential security testing/hacking device"
        )
    )
    
    // ==================== Raven Service UUIDs ====================
    // Based on GainSec research - raven_configurations.json
    // Firmware versions 1.1.7, 1.2.0, 1.3.1
    data class RavenServiceInfo(
        val uuid: UUID,
        val name: String,
        val description: String,
        val dataExposed: String,
        val firmwareVersions: List<String>
    )
    
    val ravenServiceUuids = listOf(
        RavenServiceInfo(
            uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
            name = "Device Information",
            description = "Standard BLE Device Information Service",
            dataExposed = "Serial number, model number, firmware version, manufacturer",
            firmwareVersions = listOf("1.1.x", "1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"),
            name = "GPS Location",
            description = "Real-time GPS coordinates of the device",
            dataExposed = "Latitude, longitude, altitude, GPS fix status",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"),
            name = "Power Management",
            description = "Battery and solar panel status",
            dataExposed = "Battery level, charging status, solar input voltage",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003300-0000-1000-8000-00805f9b34fb"),
            name = "Network Status",
            description = "Cellular and WiFi connectivity info",
            dataExposed = "LTE signal strength, carrier, data usage, WiFi status",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"),
            name = "Upload Statistics",
            description = "Data transmission metrics",
            dataExposed = "Bytes uploaded, detection count, last upload time",
            firmwareVersions = listOf("1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"),
            name = "Error/Diagnostics",
            description = "System diagnostics and error logs",
            dataExposed = "Error codes, system health, diagnostic data",
            firmwareVersions = listOf("1.3.x")
        ),
        // Legacy services (firmware 1.1.x)
        RavenServiceInfo(
            uuid = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"),
            name = "Health Thermometer (Legacy)",
            description = "Repurposed standard BLE service",
            dataExposed = "Device temperature, environmental data",
            firmwareVersions = listOf("1.1.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"),
            name = "Location/Navigation (Legacy)",
            description = "Repurposed standard BLE location service",
            dataExposed = "Basic location data",
            firmwareVersions = listOf("1.1.x")
        )
    )
    
    val ravenServiceUuidSet: Set<UUID> = ravenServiceUuids.map { it.uuid }.toSet()

    // ==================== CACHED REGEX PATTERNS ====================
    // Pre-compile all regex patterns for performance - avoids recompilation on every match

    /**
     * Cache of compiled Regex objects for SSID patterns.
     * Lazy-initialized on first access to avoid startup overhead.
     */
    private val ssidPatternRegexCache: Map<String, Regex> by lazy {
        ssidPatterns.mapNotNull { pattern ->
            try {
                pattern.pattern to Regex(pattern.pattern)
            } catch (e: Exception) {
                null // Skip invalid patterns
            }
        }.toMap()
    }

    /**
     * Cache of compiled Regex objects for BLE name patterns.
     * Lazy-initialized on first access to avoid startup overhead.
     */
    private val bleNamePatternRegexCache: Map<String, Regex> by lazy {
        bleNamePatterns.mapNotNull { pattern ->
            try {
                pattern.pattern to Regex(pattern.pattern)
            } catch (e: Exception) {
                null // Skip invalid patterns
            }
        }.toMap()
    }

    /**
     * Get a cached compiled Regex for the given pattern string.
     * Returns null if the pattern is invalid.
     */
    private fun getCachedSsidRegex(patternString: String): Regex? {
        return ssidPatternRegexCache[patternString]
    }

    /**
     * Get a cached compiled Regex for the given BLE name pattern string.
     * Returns null if the pattern is invalid.
     */
    private fun getCachedBleNameRegex(patternString: String): Regex? {
        return bleNamePatternRegexCache[patternString]
    }

    /**
     * Estimate Raven firmware version based on advertised services
     */
    fun estimateRavenFirmwareVersion(serviceUuids: List<UUID>): String {
        val hasLegacyHealth = serviceUuids.contains(UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"))
        val hasLegacyLocation = serviceUuids.contains(UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"))
        val hasGps = serviceUuids.contains(UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"))
        val hasPower = serviceUuids.contains(UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"))
        val hasUpload = serviceUuids.contains(UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"))
        val hasError = serviceUuids.contains(UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"))
        
        return when {
            hasUpload || hasError -> "1.3.x (Latest - Full diagnostics)"
            hasGps && hasPower -> "1.2.x (GPS + Power monitoring)"
            hasLegacyHealth || hasLegacyLocation -> "1.1.x (Legacy firmware)"
            else -> "Unknown version"
        }
    }
    
    // ==================== MAC Address Prefixes (OUI) ====================
    // LTE modems commonly used in surveillance equipment
    // Flock uses cellular connectivity - these are modem manufacturer OUIs
    val macPrefixes = listOf(
        // Quectel - Common LTE modem manufacturer
        MacPrefix("50:29:4D", DeviceType.FLOCK_SAFETY_CAMERA, "Quectel (LTE Modem)", 70,
            "Quectel LTE modem - commonly used in Flock cameras"),
        MacPrefix("86:25:19", DeviceType.FLOCK_SAFETY_CAMERA, "Quectel (LTE Modem)", 70,
            "Quectel cellular module"),

        // Telit - Another common IoT/LTE modem maker
        MacPrefix("00:14:2D", DeviceType.UNKNOWN_SURVEILLANCE, "Telit (LTE Modem)", 65,
            "Telit cellular modem - used in IoT surveillance"),
        MacPrefix("D8:C7:71", DeviceType.UNKNOWN_SURVEILLANCE, "Telit Wireless", 65,
            "Telit wireless module"),

        // ==================== Fleet Vehicle OUIs (Police Car WiFi APs) ====================
        // Sierra Wireless - Common in police/fleet vehicles
        MacPrefix("00:0E:8E", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 85,
            "Sierra Wireless - common in police/fleet vehicle mobile routers"),
        MacPrefix("00:11:75", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 85,
            "Sierra Wireless - fleet vehicle mobile hotspot"),
        MacPrefix("00:14:3E", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 80,
            "Sierra Wireless modem - IoT/M2M/fleet applications"),
        MacPrefix("00:A0:D5", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 80,
            "Sierra Wireless cellular module"),

        // Cradlepoint - Popular for mobile command/surveillance vehicles
        MacPrefix("00:30:44", DeviceType.FLEET_VEHICLE, "Cradlepoint", 85,
            "Cradlepoint - mobile router common in police/emergency vehicles"),
        MacPrefix("00:10:8B", DeviceType.FLEET_VEHICLE, "Cradlepoint", 85,
            "Cradlepoint router - often used for mobile surveillance/command"),
        MacPrefix("EC:F4:51", DeviceType.FLEET_VEHICLE, "Cradlepoint", 80,
            "Cradlepoint NetCloud - fleet management router"),

        // Digi International - Fleet telematics
        MacPrefix("00:40:9D", DeviceType.FLEET_VEHICLE, "Digi International", 75,
            "Digi - fleet telematics and mobile connectivity"),

        // CalAmp - Vehicle tracking and fleet management
        MacPrefix("00:07:F9", DeviceType.FLEET_VEHICLE, "CalAmp", 80,
            "CalAmp - vehicle tracking and fleet management"),

        // Geotab - Fleet telematics
        MacPrefix("00:1E:C0", DeviceType.FLEET_VEHICLE, "Geotab", 75,
            "Geotab - fleet telematics device"),

        // u-blox - GPS/cellular modules
        MacPrefix("D4:CA:6E", DeviceType.UNKNOWN_SURVEILLANCE, "u-blox", 60,
            "u-blox cellular/GPS module"),

        // Raspberry Pi (used in DIY/prototype ALPR)
        MacPrefix("B8:27:EB", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi - potential DIY ALPR system"),
        MacPrefix("DC:A6:32", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi 4 - potential DIY surveillance"),
        MacPrefix("E4:5F:01", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi - IoT device"),

        // ==================== Axon / Body Camera Manufacturer OUIs ====================
        // Nordic Semiconductor - used in Axon body cameras and Signal devices
        // Note: Nordic's IEEE OUI is F4:CE:36 (not 00:59 which is their BLE Company ID)
        MacPrefix("F4:CE:36", DeviceType.AXON_POLICE_TECH, "Nordic Semiconductor", 75,
            "Nordic Semiconductor - common in Axon body cameras/Signal triggers"),
        MacPrefix("C0:A5:3E", DeviceType.AXON_POLICE_TECH, "Nordic Semiconductor", 75,
            "Nordic Semiconductor BLE - Axon equipment"),
        MacPrefix("F0:5C:D5", DeviceType.AXON_POLICE_TECH, "Nordic Semiconductor", 70,
            "Nordic Semiconductor - police equipment BLE"),

        // Texas Instruments - used in some body cameras
        MacPrefix("D0:39:72", DeviceType.BODY_CAMERA, "Texas Instruments", 65,
            "TI BLE module - potential body camera"),

        // Dialog Semiconductor - BLE chips in wearables/cameras
        MacPrefix("80:EA:CA", DeviceType.BODY_CAMERA, "Dialog Semiconductor", 60,
            "Dialog Semiconductor - BLE wearable/camera")
    )
    
    data class MacPrefix(
        val prefix: String,
        val deviceType: DeviceType,
        val manufacturer: String,
        val threatScore: Int,
        val description: String = "",
        val sourceUrl: String? = null
    )
    
    /**
     * Check if a MAC address matches any known prefix
     */
    fun matchMacPrefix(macAddress: String): MacPrefix? {
        val normalizedMac = macAddress.uppercase().replace("-", ":")
        return macPrefixes.find { normalizedMac.startsWith(it.prefix.uppercase()) }
    }
    
    /**
     * Check if SSID matches any known pattern.
     * Uses pre-compiled cached Regex objects for performance.
     */
    fun matchSsidPattern(ssid: String): DetectionPattern? {
        return ssidPatterns.find { pattern ->
            getCachedSsidRegex(pattern.pattern)?.matches(ssid) ?: false
        }
    }

    /**
     * Check if BLE device name matches any known pattern.
     * Uses pre-compiled cached Regex objects for performance.
     */
    fun matchBleNamePattern(deviceName: String): DetectionPattern? {
        return bleNamePatterns.find { pattern ->
            getCachedBleNameRegex(pattern.pattern)?.matches(deviceName) ?: false
        }
    }
    
    /**
     * Check if any service UUIDs match Raven patterns
     */
    fun matchRavenServices(serviceUuids: List<UUID>): List<RavenServiceInfo> {
        return ravenServiceUuids.filter { it.uuid in serviceUuids }
    }
    
    /**
     * Check if this is a Raven device based on service UUIDs
     */
    fun isRavenDevice(serviceUuids: List<UUID>): Boolean {
        // Need at least 2 Raven-specific services to confirm
        val matchCount = serviceUuids.count { it in ravenServiceUuidSet }
        return matchCount >= 2
    }
    
    /**
     * Get detailed info about a device type
     */
    fun getDeviceTypeInfo(deviceType: DeviceType): DeviceTypeInfo {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> DeviceTypeInfo(
                name = "Flock Safety ALPR Camera",
                shortDescription = "Automated License Plate Reader",
                fullDescription = "Flock Safety cameras capture images of vehicles and license plates. " +
                    "They use 'Vehicle Fingerprint' technology to identify make, model, color, and " +
                    "distinguishing features. Data is stored for 30 days and shared with law enforcement. " +
                    "Over 5,000 communities use Flock with 20+ billion monthly plate scans.",
                capabilities = listOf(
                    "License plate capture (up to 100 mph)",
                    "Vehicle make/model/color identification",
                    "Vehicle 'fingerprinting' (dents, stickers, etc.)",
                    "Real-time hotlist alerts",
                    "Integration with NCIC database",
                    "Cross-jurisdiction data sharing"
                ),
                privacyConcerns = listOf(
                    "Mass surveillance of vehicle movements",
                    "30-day data retention (may vary by jurisdiction)",
                    "Shared across law enforcement network",
                    "No warrant required for access",
                    "Can be integrated with Palantir"
                )
            )
            DeviceType.RAVEN_GUNSHOT_DETECTOR -> DeviceTypeInfo(
                name = "Raven Acoustic Gunshot Detector",
                shortDescription = "Audio Surveillance Device",
                fullDescription = "Raven devices (by Flock Safety, similar to ShotSpotter) continuously " +
                    "record audio in 5-second clips, using AI to detect gunfire. As of October 2025, " +
                    "Flock announced Ravens will also listen for 'human distress' (screaming). " +
                    "Solar-powered with cellular connectivity.",
                capabilities = listOf(
                    "Continuous audio monitoring",
                    "Gunshot detection and location",
                    "Human distress/scream detection (new)",
                    "GPS location tracking",
                    "Instant alerts to law enforcement",
                    "AI-powered audio analysis"
                ),
                privacyConcerns = listOf(
                    "Constant audio surveillance",
                    "'Human distress' detection is vague",
                    "Audio recordings may capture conversations",
                    "No consent from recorded individuals",
                    "Potential for false positives"
                )
            )
            DeviceType.PENGUIN_SURVEILLANCE -> DeviceTypeInfo(
                name = "Penguin Surveillance Device",
                shortDescription = "Mobile ALPR System",
                fullDescription = "Penguin devices are mobile surveillance systems often mounted on vehicles.",
                capabilities = listOf("Mobile license plate reading", "Vehicle tracking"),
                privacyConcerns = listOf("Mobile mass surveillance", "Covert operation")
            )
            DeviceType.PIGVISION_SYSTEM -> DeviceTypeInfo(
                name = "Pigvision System",
                shortDescription = "Surveillance Camera System",
                fullDescription = "Pigvision surveillance camera network.",
                capabilities = listOf("Video surveillance", "License plate capture"),
                privacyConcerns = listOf("Mass surveillance", "Data retention unknown")
            )
            DeviceType.MOTOROLA_POLICE_TECH -> DeviceTypeInfo(
                name = "Motorola Police Technology",
                shortDescription = "Law Enforcement Equipment",
                fullDescription = "Motorola Solutions provides extensive police technology including " +
                    "body cameras (V300/V500), in-car video systems, APX radios, and the Vigilant ALPR platform. " +
                    "Evidence is typically stored in their CommandCentral platform.",
                capabilities = listOf(
                    "Body-worn camera recording",
                    "In-car video systems",
                    "Two-way radio communications",
                    "ALPR (Vigilant platform)",
                    "Real-time video streaming",
                    "GPS location tracking"
                ),
                privacyConcerns = listOf(
                    "Continuous recording capability",
                    "Cloud evidence storage",
                    "Cross-agency data sharing",
                    "Facial recognition integration potential"
                )
            )
            DeviceType.AXON_POLICE_TECH -> DeviceTypeInfo(
                name = "Axon Police Technology",
                shortDescription = "Body Cameras & TASERs",
                fullDescription = "Axon (formerly TASER International) is the dominant body camera provider " +
                    "for US law enforcement. They also make TASERs, in-car cameras, and the Evidence.com " +
                    "cloud storage platform. Axon has been expanding into AI-powered features.",
                capabilities = listOf(
                    "Body camera recording (Body 2/3/4)",
                    "TASER deployment logging",
                    "Automatic recording triggers",
                    "Evidence.com cloud storage",
                    "Real-time streaming (Axon Respond)",
                    "AI-powered redaction and transcription"
                ),
                privacyConcerns = listOf(
                    "Massive video evidence database",
                    "AI/facial recognition features",
                    "Third-party cloud storage",
                    "Potential for covert recording",
                    "Data retention policies vary by agency"
                )
            )
            DeviceType.L3HARRIS_SURVEILLANCE -> DeviceTypeInfo(
                name = "L3Harris Surveillance",
                shortDescription = "Advanced Surveillance Systems",
                fullDescription = "L3Harris Technologies manufactures advanced surveillance equipment " +
                    "including cell site simulators (StingRay/Hailstorm), radio systems, and ISR " +
                    "(Intelligence, Surveillance, and Reconnaissance) equipment.",
                capabilities = listOf(
                    "Radio communications systems",
                    "Electronic surveillance",
                    "SIGINT capabilities",
                    "Tactical communications"
                ),
                privacyConcerns = listOf(
                    "Military-grade surveillance tech",
                    "Cell site simulator manufacturer",
                    "Little public accountability"
                )
            )
            DeviceType.CELLEBRITE_FORENSICS -> DeviceTypeInfo(
                name = "Cellebrite Mobile Forensics",
                shortDescription = "Phone Data Extraction Device",
                fullDescription = "Cellebrite UFED (Universal Forensic Extraction Device) is the most widely " +
                    "used mobile forensics tool by law enforcement worldwide. It can extract data from " +
                    "locked phones, including deleted content, app data, passwords, and encrypted messages.\n\n" +
                    "COST: $15,000-$30,000+ per unit (law enforcement, border agents, corporate security)\n\n" +
                    "IF DETECTED NEARBY: This may indicate an active forensic examination. Could be at a " +
                    "police station, border crossing, or mobile forensics unit. Proximity to you suggests " +
                    "potential device seizure risk.",
                capabilities = listOf(
                    "Bypass screen locks on most phones (even newer iPhones with some models)",
                    "Extract ALL data: messages, photos, videos, documents",
                    "Recover DELETED content (messages, photos, call logs)",
                    "Extract app data from: Signal, WhatsApp, Telegram, Instagram, etc.",
                    "Capture passwords, authentication tokens, browser history",
                    "Access encrypted app databases",
                    "Extract cloud account credentials for remote extraction",
                    "Full physical image of device storage",
                    "Geolocation history reconstruction"
                ),
                privacyConcerns = listOf(
                    "Complete phone data extraction - nothing is private",
                    "Recovers content you thought was deleted",
                    "Can access encrypted messaging apps via device extraction",
                    "Border agents can use WITHOUT a warrant",
                    "Some jurisdictions allow at traffic stops",
                    "Data may be retained indefinitely",
                    "Can extract cloud passwords to access online accounts",
                    "Creates complete forensic image for later analysis"
                ),
                recommendations = listOf(
                    "Know your rights: You can refuse consent (5th Amendment) but device may be seized",
                    "At borders: Different rules apply, consent may be compelled",
                    "Strong alphanumeric passwords are harder to crack than PINs",
                    "Enable full-disk encryption",
                    "Consider 'travel mode' devices for sensitive border crossings",
                    "Signal's disappearing messages are harder to recover",
                    "iPhone's USB Restricted Mode helps prevent extraction",
                    "Regular phone reboots help (data protection is stronger after reboot)"
                )
            )
            DeviceType.BODY_CAMERA -> DeviceTypeInfo(
                name = "Body-Worn Camera",
                shortDescription = "Police Body Camera",
                fullDescription = "Body-worn cameras record officer interactions with the public. " +
                    "While intended for accountability, they also create extensive surveillance footage " +
                    "of everyone officers encounter.",
                capabilities = listOf(
                    "Video and audio recording",
                    "GPS location logging",
                    "Automatic activation triggers",
                    "Real-time streaming capability",
                    "Night vision/low-light recording"
                ),
                privacyConcerns = listOf(
                    "Records bystanders without consent",
                    "Footage retention varies (30 days to years)",
                    "Can be used for facial recognition",
                    "Officers can review before writing reports",
                    "Release policies often favor police"
                )
            )
            DeviceType.POLICE_RADIO -> DeviceTypeInfo(
                name = "Police Radio System",
                shortDescription = "Law Enforcement Communications",
                fullDescription = "Modern police radios use encrypted digital protocols and often " +
                    "include GPS tracking, emergency alerts, and data transmission capabilities.",
                capabilities = listOf(
                    "Encrypted voice communications",
                    "GPS location tracking",
                    "Data transmission",
                    "Emergency signaling",
                    "Inter-agency communication"
                ),
                privacyConcerns = listOf(
                    "Encryption prevents public monitoring",
                    "Location tracking of officers/suspects",
                    "Interoperability with surveillance systems"
                )
            )
            DeviceType.STINGRAY_IMSI -> DeviceTypeInfo(
                name = "Cell Site Simulator (StingRay)",
                shortDescription = "IMSI Catcher / Fake Cell Tower",
                fullDescription = "Cell site simulators (StingRay, Hailstorm, Kingfish, Crossbow, etc.) are " +
                    "portable devices that impersonate legitimate cell towers to intercept mobile communications. " +
                    "When your phone connects to one, it captures your IMSI (unique SIM identifier), IMEI (device ID), " +
                    "and can intercept calls, texts, and data. These devices affect ALL phones in range (typically 1-2 km), " +
                    "not just the target, making them a mass surveillance tool.\n\n" +
                    "🔍 THIS DETECTION was triggered by anomalous cellular behavior on your device - " +
                    "your phone may have experienced an encryption downgrade, unexpected tower switch, " +
                    "or connected to a suspicious network identifier.",
                capabilities = listOf(
                    "Capture IMSI/IMEI from all phones in range (~1-2 km radius)",
                    "Track phone locations to within a few meters",
                    "Intercept calls, SMS, and data traffic",
                    "Force 4G/5G phones to downgrade to 2G (weak/no encryption)",
                    "Perform man-in-the-middle attacks on communications",
                    "Deny cell service selectively or entirely",
                    "Identify phone make, model, and installed apps",
                    "Clone phone identifiers for impersonation"
                ),
                privacyConcerns = listOf(
                    "Mass surveillance - captures data from EVERYONE nearby, not just targets",
                    "Used under NDA - police often hide usage from courts and defense attorneys",
                    "Can intercept encrypted app traffic via downgrade attacks",
                    "No warrant required in many jurisdictions (pen register theory)",
                    "Disrupts legitimate cell service for entire area",
                    "Data retention policies are opaque or nonexistent",
                    "Often deployed at protests, political events, and public gatherings",
                    "FBI requires local police to drop cases rather than reveal usage"
                ),
                recommendations = listOf(
                    "🛡️ IMMEDIATE: Enable airplane mode, then re-enable only WiFi if needed",
                    "📱 Use Signal, WhatsApp, or other E2E encrypted apps for sensitive communications",
                    "📍 Note your location and time - document for potential legal challenges",
                    "🚶 Leave the area if possible - StingRays have limited range (~1-2 km)",
                    "⚙️ Disable 2G on your phone if supported (Settings → Network → Preferred type → LTE/5G only)",
                    "🔒 Avoid making regular phone calls or SMS - use encrypted messaging instead",
                    "📸 Look for suspicious vehicles (vans, SUVs) with antennas or running generators",
                    "⚠️ FALSE POSITIVE? This could also be triggered by poor coverage, moving between towers, or network maintenance"
                )
            )
            DeviceType.ROGUE_AP -> DeviceTypeInfo(
                name = "Rogue Access Point",
                shortDescription = "Unauthorized WiFi Access Point",
                fullDescription = "A rogue access point is an unauthorized wireless device that may be " +
                    "attempting to intercept network traffic. This could be an 'evil twin' attack, " +
                    "karma attack, or surveillance equipment designed to capture communications.",
                capabilities = listOf(
                    "Intercept wireless traffic",
                    "Capture login credentials",
                    "Man-in-the-middle attacks",
                    "Session hijacking",
                    "SSL stripping"
                ),
                privacyConcerns = listOf(
                    "All network traffic may be captured",
                    "Credentials and sensitive data at risk",
                    "Location tracking through WiFi",
                    "Device fingerprinting"
                ),
                recommendations = listOf(
                    "🛡️ Disconnect from this network immediately",
                    "🔒 Use a VPN for all internet activity",
                    "📱 Verify network authenticity before connecting",
                    "⚠️ Avoid entering sensitive information"
                )
            )
            DeviceType.HIDDEN_CAMERA -> DeviceTypeInfo(
                name = "Hidden Camera / Spy Camera",
                shortDescription = "Covert Video Surveillance Device",
                fullDescription = "A WiFi-enabled hidden camera has been detected through its network " +
                    "signature. These devices are often disguised as everyday objects and can stream " +
                    "video to remote viewers or cloud storage.\n\n" +
                    "COMMON DISGUISES:\n" +
                    "- Smoke detectors, carbon monoxide detectors\n" +
                    "- Clocks (alarm clocks, wall clocks)\n" +
                    "- USB chargers and power adapters\n" +
                    "- Electrical outlets and light switches\n" +
                    "- Picture frames and mirrors\n" +
                    "- Tissue boxes, plants, stuffed animals\n" +
                    "- Air purifiers, speakers, routers\n\n" +
                    "WHERE TO CHECK:\n" +
                    "- Airbnbs, hotels, vacation rentals\n" +
                    "- Changing rooms, bathrooms\n" +
                    "- Areas facing beds, showers, toilets\n" +
                    "- Any object with direct line of sight to private areas",
                capabilities = listOf(
                    "HD video recording (720p to 4K)",
                    "Live streaming over WiFi/4G",
                    "Night vision / IR recording",
                    "Motion-activated recording",
                    "Cloud storage upload",
                    "Remote viewing via app",
                    "Audio recording (some models)",
                    "Long battery life or wall-powered"
                ),
                privacyConcerns = listOf(
                    "Illegal in private spaces without consent",
                    "May be recording intimate moments",
                    "Footage can be sold, shared, or used for blackmail",
                    "Common in Airbnb/rental horror stories",
                    "Remote viewer may be watching in real-time",
                    "Cloud storage means footage persists even if camera removed"
                ),
                recommendations = listOf(
                    "IR DETECTION: Use your phone camera (front camera works better) to scan for IR LEDs - they appear as purple/white glow in dark",
                    "PHYSICAL INSPECTION: Check smoke detectors, clocks, outlets, and objects facing bed/bathroom",
                    "LENS REFLECTION: Use flashlight - camera lenses reflect light distinctively",
                    "RF DETECTOR: Dedicated RF detectors can find wireless cameras",
                    "SIGNAL STRENGTH: Move around room - strongest signal indicates camera location",
                    "NETWORK SCAN: Note MAC address to identify manufacturer",
                    "IF FOUND: Document with photos, DO NOT touch, contact police",
                    "LEGAL: Recording in private spaces without consent is illegal - report to authorities"
                )
            )
            DeviceType.SURVEILLANCE_VAN -> DeviceTypeInfo(
                name = "Surveillance Van / Mobile Surveillance",
                shortDescription = "Mobile Surveillance Platform",
                fullDescription = "A mobile hotspot has been detected matching patterns associated with " +
                    "surveillance vehicles. This could be law enforcement, federal agencies, private " +
                    "investigators, or corporate security.\n\n" +
                    "IMPORTANT: Real surveillance operations use BLAND, generic SSIDs - not 'FBI_Van' " +
                    "(that's a joke). Look for: generic fleet names, manufacturer defaults (Sierra Wireless, " +
                    "Cradlepoint), or suspiciously plain hotspot names.\n\n" +
                    "KEY INDICATOR: Same SSID appearing at multiple of YOUR locations (home, work, gym) " +
                    "is a strong signal of targeted surveillance.\n\n" +
                    "WHO MIGHT OPERATE:\n" +
                    "- FBI, DEA, ATF, ICE, USMS\n" +
                    "- State and local police\n" +
                    "- Private investigators\n" +
                    "- Corporate security/counterintelligence",
                capabilities = listOf(
                    "Video/photo surveillance (telephoto, night vision)",
                    "Audio surveillance (parabolic mics, laser mics)",
                    "Cell site simulator (StingRay) operation",
                    "WiFi/Bluetooth interception",
                    "License plate readers (mobile ALPR)",
                    "GPS tracking coordination",
                    "Mobile command and control",
                    "Extended duration stakeout capability"
                ),
                privacyConcerns = listOf(
                    "Targeted surveillance of specific person/location",
                    "May deploy multiple surveillance technologies",
                    "Can follow subjects across jurisdictions",
                    "Often operate in unmarked vehicles (vans, SUVs, work trucks)",
                    "May include covert entry teams",
                    "Video/audio recording of activities",
                    "Cell phone interception capability"
                ),
                recommendations = listOf(
                    "CONFIRM: Does this SSID appear at multiple of YOUR locations?",
                    "LOCATE: Walk around - signal strength helps identify source vehicle",
                    "DOCUMENT: Note vehicle description (make, model, plate, location, time)",
                    "LOOK FOR: Vans/SUVs with running engines, unusual antennas, tinted windows",
                    "PATTERN: Track appearances over multiple days",
                    "COMMUNICATIONS: Use encrypted messaging (Signal) if concerned",
                    "LEGAL: Consult attorney if you believe you're under surveillance",
                    "DO NOT: Approach or confront suspected surveillance vehicle"
                )
            )
            DeviceType.TRACKING_DEVICE -> DeviceTypeInfo(
                name = "Tracking Device / Following Network",
                shortDescription = "Location Tracking via WiFi",
                fullDescription = "A WiFi network has been detected that appears to be following your location. " +
                    "This is determined by the same network appearing at multiple distinct locations you visit.\n\n" +
                    "THIS IS A STRONG INDICATOR OF SURVEILLANCE if:\n" +
                    "- Same BSSID (MAC address) seen at 3+ of your locations\n" +
                    "- Network appears at both home AND work\n" +
                    "- Signal strength varies but network persists\n" +
                    "- Pattern correlates with your movement\n\n" +
                    "POSSIBLE EXPLANATIONS:\n" +
                    "1. Surveillance team using mobile hotspot\n" +
                    "2. GPS/WiFi tracker planted on your vehicle\n" +
                    "3. Tracking device in belongings\n" +
                    "4. Coincidental: neighbor/coworker with same commute (check timing patterns)\n" +
                    "5. Public transit WiFi (bus, train - if pattern matches routes)",
                capabilities = listOf(
                    "Continuous GPS/cellular location tracking",
                    "Movement pattern analysis",
                    "Real-time location updates to monitor",
                    "Geofence alerts (notify when entering/leaving areas)",
                    "Historical location logging",
                    "Long battery life (weeks to months)",
                    "Magnetic mounting for vehicles"
                ),
                privacyConcerns = listOf(
                    "Complete location history being logged",
                    "Daily routine and patterns exposed",
                    "Home, work, and frequent locations known",
                    "Relationships inferred from location data",
                    "May be part of larger surveillance operation",
                    "Could indicate stalking or harassment",
                    "Data may be shared with multiple parties"
                ),
                recommendations = listOf(
                    "VERIFY: Check if network appears at 3+ distinct locations you visit",
                    "FALSE POSITIVE CHECK: Is this a neighbor/coworker with same commute?",
                    "VEHICLE CHECK: Inspect wheel wells, undercarriage, bumpers, trunk",
                    "BELONGINGS: Check bags, briefcase, gifts you received",
                    "OBD PORT: Check for device plugged into car's OBD-II port",
                    "VARY ROUTINE: Take different route - does network still follow?",
                    "DOCUMENT: Log all sighting locations, times, and signal strengths",
                    "LEGAL: Police generally need warrant for GPS tracking (US v. Jones)",
                    "STALKING: If unauthorized, this is criminal in all states",
                    "DO NOT REMOVE: If found, document first - may be evidence"
                )
            )
            DeviceType.RF_JAMMER -> DeviceTypeInfo(
                name = "RF Jammer",
                shortDescription = "Radio Frequency Jammer",
                fullDescription = "A device that disrupts wireless communications by emitting interference. " +
                    "RF jammers are illegal in most jurisdictions but may be used by law enforcement or criminals.",
                capabilities = listOf(
                    "Block cellular signals",
                    "Disrupt WiFi communications",
                    "Prevent GPS tracking",
                    "Interfere with Bluetooth"
                ),
                privacyConcerns = listOf(
                    "Prevents emergency calls",
                    "Blocks legitimate communications",
                    "May indicate criminal activity nearby",
                    "Illegal to operate in most places"
                ),
                recommendations = listOf(
                    "🚨 Leave area - may indicate robbery or attack",
                    "📍 Note location and time",
                    "📞 Report to authorities (from outside range)",
                    "⚠️ Do not attempt to locate the device"
                )
            )
            DeviceType.DRONE -> DeviceTypeInfo(
                name = "Drone/UAV",
                shortDescription = "Unmanned Aerial Vehicle",
                fullDescription = "A drone detected via its WiFi control signal. Drones can carry cameras " +
                    "and other sensors for aerial surveillance.",
                capabilities = listOf(
                    "Aerial video/photo capture",
                    "Thermal imaging",
                    "GPS tracking",
                    "Extended range surveillance",
                    "Face recognition capability"
                ),
                privacyConcerns = listOf(
                    "Surveillance from above",
                    "Difficult to detect visually",
                    "Can follow subjects",
                    "May record private property"
                ),
                recommendations = listOf(
                    "🔍 Look up to locate the drone",
                    "📱 Use the signal strength to find operator",
                    "🚨 Report if over private property",
                    "🏠 Move indoors if concerned"
                )
            )
            DeviceType.SURVEILLANCE_INFRASTRUCTURE -> DeviceTypeInfo(
                name = "Surveillance Infrastructure",
                shortDescription = "Fixed Surveillance System",
                fullDescription = "A concentration of surveillance devices indicating organized monitoring " +
                    "infrastructure such as camera networks or sensor arrays.",
                capabilities = listOf(
                    "Multiple camera coverage",
                    "License plate recognition",
                    "Face recognition",
                    "Behavior analysis",
                    "Cross-camera tracking"
                ),
                privacyConcerns = listOf(
                    "Comprehensive area monitoring",
                    "Long-term data retention",
                    "Automated tracking capabilities",
                    "Integration with law enforcement databases"
                )
            )
            DeviceType.ULTRASONIC_BEACON -> DeviceTypeInfo(
                name = "Ultrasonic Beacon",
                shortDescription = "Ultrasonic Tracking Device",
                fullDescription = "An ultrasonic beacon emitting inaudible sounds for cross-device tracking. " +
                    "These are used by advertisers to link your devices and track you across locations.",
                capabilities = listOf(
                    "Cross-device tracking",
                    "Location tracking in stores",
                    "Ad targeting coordination",
                    "Linking anonymous browsing to identity"
                ),
                privacyConcerns = listOf(
                    "Tracks without visual indication",
                    "Links all your devices together",
                    "Retail location tracking",
                    "Advertising profile building",
                    "Works even with WiFi/Bluetooth off"
                ),
                recommendations = listOf(
                    "🔇 These signals are inaudible to humans",
                    "📱 Some apps can block ultrasonic tracking",
                    "🏪 Common in retail stores and TV ads",
                    "🔒 Consider ultrasonic firewall apps"
                )
            )
            DeviceType.POLICE_VEHICLE -> DeviceTypeInfo(
                name = "Police/Emergency Vehicle",
                shortDescription = "Emergency Vehicle Detected",
                fullDescription = "A police car, ambulance, or fire truck has been detected nearby via its " +
                    "emergency lighting system's Bluetooth connection. Modern emergency vehicles use BLE " +
                    "to synchronize their lightbars (Whelen CenCom, Federal Signal, etc.) and may also " +
                    "have body camera triggers (Axon Signal) that activate when sirens are turned on.",
                capabilities = listOf(
                    "Emergency lightbar synchronization",
                    "Automatic body camera activation",
                    "Siren/PA system integration",
                    "In-car video triggering",
                    "GPS/AVL location reporting"
                ),
                privacyConcerns = listOf(
                    "Officers may be actively recording",
                    "Vehicle likely has dash cameras",
                    "ALPR systems often installed",
                    "Location data transmitted to dispatch"
                ),
                recommendations = listOf(
                    "🚔 Police or emergency vehicle is nearby",
                    "📹 Assume you are being recorded",
                    "📍 Note time and location if relevant",
                    "🚗 May be stationary or moving through area"
                )
            )
            DeviceType.FLEET_VEHICLE -> DeviceTypeInfo(
                name = "Fleet Vehicle",
                shortDescription = "Commercial/Government Fleet Vehicle",
                fullDescription = "A vehicle with fleet management hardware has been detected. This could be " +
                    "a police vehicle, government car, utility truck, or commercial fleet vehicle. These " +
                    "vehicles often have WiFi hotspots using Sierra Wireless or Cradlepoint routers, which " +
                    "are commonly used by law enforcement for mobile data terminals and surveillance equipment.",
                capabilities = listOf(
                    "Mobile WiFi hotspot for in-vehicle systems",
                    "GPS tracking and route logging",
                    "Mobile data terminal connectivity",
                    "Potential ALPR/camera data uplink",
                    "Fleet management and dispatch integration"
                ),
                privacyConcerns = listOf(
                    "May be an unmarked police vehicle",
                    "Could have surveillance equipment",
                    "Vehicle movements are tracked",
                    "Hidden SSID networks are common"
                ),
                recommendations = listOf(
                    "🚐 Fleet vehicle detected via mobile router",
                    "👀 Could be police, utility, or commercial",
                    "📶 Strong signal = vehicle is close",
                    "🔍 Hidden SSIDs are suspicious"
                )
            )
            DeviceType.SATELLITE_NTN -> DeviceTypeInfo(
                name = "Satellite NTN Device",
                shortDescription = "Non-Terrestrial Network Connection",
                fullDescription = "Connection detected via satellite-based Non-Terrestrial Network (NTN). " +
                    "This could be legitimate D2D service (T-Mobile Starlink, Skylo) or potentially " +
                    "suspicious if unexpected in an area with good terrestrial coverage.",
                capabilities = listOf(
                    "SMS/MMS messaging (provider dependent)",
                    "Emergency SOS services",
                    "Location sharing",
                    "Limited data for select apps"
                ),
                privacyConcerns = listOf(
                    "Satellite connections can be spoofed by ground stations",
                    "NTN parameters can reveal user location",
                    "Unknown satellites may intercept communications"
                ),
                recommendations = listOf(
                    "Verify expected satellite provider",
                    "Check if terrestrial coverage is available",
                    "Monitor for timing anomalies"
                )
            )
            DeviceType.RF_INTERFERENCE -> DeviceTypeInfo(
                name = "RF Interference",
                shortDescription = "Radio Frequency Interference Detected",
                fullDescription = "Significant change in the RF environment detected. This could indicate " +
                    "natural interference, new equipment nearby, or intentional signal manipulation.",
                capabilities = listOf(
                    "Disrupts wireless communications",
                    "May affect WiFi, Bluetooth, cellular",
                    "Can mask other surveillance activity"
                ),
                privacyConcerns = listOf(
                    "May be used to disrupt security systems",
                    "Could be part of surveillance operation",
                    "May precede other attacks"
                )
            )
            DeviceType.RF_ANOMALY -> DeviceTypeInfo(
                name = "RF Environment Anomaly",
                shortDescription = "Unusual RF Activity Pattern",
                fullDescription = "Unusual patterns detected in the local RF environment. This is a " +
                    "low-confidence detection that may indicate surveillance equipment or environmental changes.",
                capabilities = listOf("Varies depending on source"),
                privacyConcerns = listOf("May indicate covert RF equipment nearby")
            )
            DeviceType.HIDDEN_TRANSMITTER -> DeviceTypeInfo(
                name = "Hidden Transmitter",
                shortDescription = "Possible Covert RF Transmission",
                fullDescription = "A potential hidden RF transmitter has been detected. This could be " +
                    "a bug, tracker, or other covert transmission device.",
                capabilities = listOf(
                    "Continuous RF transmission",
                    "May transmit audio/video/data",
                    "Could be a tracking device"
                ),
                privacyConcerns = listOf(
                    "Covert audio/video surveillance",
                    "Location tracking",
                    "Data exfiltration"
                )
            )
            DeviceType.GNSS_SPOOFER -> DeviceTypeInfo(
                name = "GNSS Spoofer",
                shortDescription = "GPS/GNSS Signal Spoofing Detected",
                fullDescription = "Fake GPS/GNSS signals detected. Someone may be attempting to " +
                    "manipulate your location data or deceive GPS-dependent systems.",
                capabilities = listOf(
                    "Broadcast fake GPS coordinates",
                    "Manipulate location-based services",
                    "Deceive navigation systems"
                ),
                privacyConcerns = listOf(
                    "Location data manipulation",
                    "May be used for tracking or misdirection",
                    "Can affect geofencing and location logs"
                )
            )
            DeviceType.GNSS_JAMMER -> DeviceTypeInfo(
                name = "GNSS Jammer",
                shortDescription = "GPS/GNSS Signal Jamming Detected",
                fullDescription = "GPS/GNSS signals are being blocked or degraded. This prevents " +
                    "accurate location determination and may indicate a surveillance operation.",
                capabilities = listOf(
                    "Block GPS/GNSS signals",
                    "Prevent location tracking",
                    "Disable GPS-dependent security"
                ),
                privacyConcerns = listOf(
                    "May be used to avoid location tracking",
                    "Can indicate criminal activity nearby",
                    "Affects emergency services"
                )
            )
            DeviceType.UNKNOWN_SURVEILLANCE -> DeviceTypeInfo(
                name = "Unknown Surveillance Device",
                shortDescription = "Unidentified Surveillance Equipment",
                fullDescription = "This device matches patterns associated with surveillance equipment " +
                    "but the specific manufacturer/model is unknown.",
                capabilities = listOf("Unknown - potentially ALPR or audio surveillance"),
                privacyConcerns = listOf("Unknown data collection practices")
            )
            // Smart Home / IoT Surveillance Devices
            DeviceType.RING_DOORBELL -> DeviceTypeInfo(
                name = "Ring Doorbell/Camera",
                shortDescription = "Amazon Smart Doorbell",
                fullDescription = "Ring doorbells and cameras are connected to Amazon's Neighbors network. " +
                    "Ring has partnerships with over 2,500 police departments allowing law enforcement to " +
                    "request video footage, sometimes without a warrant.",
                capabilities = listOf(
                    "Video recording (1080p-4K)",
                    "Two-way audio",
                    "Motion detection",
                    "Police footage sharing via Neighbors"
                ),
                privacyConcerns = listOf(
                    "Footage shared with 2,500+ police agencies",
                    "Neighbors app creates surveillance network",
                    "Cloud storage on Amazon servers"
                )
            )
            DeviceType.NEST_CAMERA -> DeviceTypeInfo(
                name = "Nest/Google Camera",
                shortDescription = "Google Smart Camera",
                fullDescription = "Nest cameras are connected to Google's cloud infrastructure.",
                capabilities = listOf("HD/4K video", "AI-powered detection", "Google Home integration"),
                privacyConcerns = listOf("Data processed by Google AI", "Cloud storage", "May be subpoenaed")
            )
            DeviceType.AMAZON_SIDEWALK -> DeviceTypeInfo(
                name = "Amazon Sidewalk Device",
                shortDescription = "Amazon Mesh Network",
                fullDescription = "Amazon Sidewalk creates a shared mesh network using Echo and Ring devices.",
                capabilities = listOf("Mesh network connectivity", "Extends smart home range"),
                privacyConcerns = listOf("Shares your bandwidth", "Creates neighborhood tracking network")
            )
            DeviceType.WYZE_CAMERA -> DeviceTypeInfo(
                name = "Wyze Camera",
                shortDescription = "Budget Smart Camera",
                fullDescription = "Wyze cameras are budget-friendly smart home cameras with cloud connectivity.",
                capabilities = listOf("HD video", "Motion detection", "Cloud storage"),
                privacyConcerns = listOf("Cloud-dependent", "Data breach history")
            )
            DeviceType.ARLO_CAMERA -> DeviceTypeInfo(
                name = "Arlo Camera",
                shortDescription = "Wireless Security Camera",
                fullDescription = "Arlo wireless security cameras with cloud storage and AI features.",
                capabilities = listOf("4K video", "Wire-free operation", "AI detection"),
                privacyConcerns = listOf("Cloud storage required", "Subscription model")
            )
            DeviceType.EUFY_CAMERA -> DeviceTypeInfo(
                name = "Eufy Camera",
                shortDescription = "Anker Security Camera",
                fullDescription = "Eufy security cameras advertise local storage but have been found to send data to cloud.",
                capabilities = listOf("Local storage option", "AI detection"),
                privacyConcerns = listOf("Misleading local storage claims", "Unencrypted cloud uploads discovered")
            )
            DeviceType.BLINK_CAMERA -> DeviceTypeInfo(
                name = "Blink Camera",
                shortDescription = "Amazon Blink Camera",
                fullDescription = "Blink cameras are Amazon-owned, similar privacy concerns to Ring.",
                capabilities = listOf("HD video", "Battery powered", "Cloud storage"),
                privacyConcerns = listOf("Amazon ecosystem", "Cloud storage", "Police partnership potential")
            )
            DeviceType.SIMPLISAFE_DEVICE -> DeviceTypeInfo(
                name = "SimpliSafe Device",
                shortDescription = "SimpliSafe Security",
                fullDescription = "SimpliSafe home security system with professional monitoring option.",
                capabilities = listOf("Intrusion detection", "Video doorbells"),
                privacyConcerns = listOf("Professional monitoring access", "Cloud connectivity")
            )
            DeviceType.ADT_DEVICE -> DeviceTypeInfo(
                name = "ADT Security Device",
                shortDescription = "ADT Security System",
                fullDescription = "ADT is a major security provider with deep law enforcement relationships.",
                capabilities = listOf("Professional monitoring", "Video surveillance"),
                privacyConcerns = listOf("Law enforcement partnerships", "Central monitoring station")
            )
            DeviceType.VIVINT_DEVICE -> DeviceTypeInfo(
                name = "Vivint Smart Home",
                shortDescription = "Vivint Security",
                fullDescription = "Vivint smart home security with AI-powered cameras and monitoring.",
                capabilities = listOf("AI camera analytics", "Professional monitoring"),
                privacyConcerns = listOf("Professional monitoring", "AI video analysis")
            )
            // Retail/Commercial Tracking
            DeviceType.BLUETOOTH_BEACON -> DeviceTypeInfo(
                name = "Bluetooth Beacon",
                shortDescription = "Location Beacon",
                fullDescription = "Bluetooth beacons are used for indoor positioning and retail tracking.",
                capabilities = listOf("Indoor positioning", "Proximity detection"),
                privacyConcerns = listOf("Tracks movement in stores", "Links to mobile apps")
            )
            DeviceType.RETAIL_TRACKER -> DeviceTypeInfo(
                name = "Retail Tracker",
                shortDescription = "Store Tracking System",
                fullDescription = "Retail tracking systems monitor customer movement and dwell time in stores.",
                capabilities = listOf("Foot traffic analysis", "Dwell time tracking"),
                privacyConcerns = listOf("Tracks without consent", "Links to purchase data")
            )
            DeviceType.CROWD_ANALYTICS -> DeviceTypeInfo(
                name = "Crowd Analytics Sensor",
                shortDescription = "People Counting System",
                fullDescription = "Sensors that count people and analyze crowd movement patterns.",
                capabilities = listOf("People counting", "Flow analysis"),
                privacyConcerns = listOf("Mass tracking", "Behavior analysis")
            )
            DeviceType.FACIAL_RECOGNITION -> DeviceTypeInfo(
                name = "Facial Recognition System",
                shortDescription = "Face Detection Camera",
                fullDescription = "Camera system with facial recognition capabilities for identification.",
                capabilities = listOf("Face detection", "Identity matching"),
                privacyConcerns = listOf("Biometric data collection", "Identity tracking")
            )
            // Tracker Devices
            DeviceType.AIRTAG -> DeviceTypeInfo(
                name = "Apple AirTag",
                shortDescription = "Apple Tracker",
                fullDescription = "Apple AirTag Bluetooth tracker. Can be misused for stalking.",
                capabilities = listOf("Precision Finding", "Find My network"),
                privacyConcerns = listOf("Stalking potential", "Movement tracking"),
                recommendations = listOf("Check if you're being tracked unexpectedly", "iOS alerts to unknown AirTags")
            )
            DeviceType.TILE_TRACKER -> DeviceTypeInfo(
                name = "Tile Tracker",
                shortDescription = "Tile Bluetooth Tracker",
                fullDescription = "Tile Bluetooth trackers for finding lost items. Now owned by Life360.",
                capabilities = listOf("Bluetooth tracking", "Community finding"),
                privacyConcerns = listOf("Life360 data practices", "Stalking potential")
            )
            DeviceType.SAMSUNG_SMARTTAG -> DeviceTypeInfo(
                name = "Samsung SmartTag",
                shortDescription = "Samsung Tracker",
                fullDescription = "Samsung's Bluetooth tracker using Galaxy Find Network.",
                capabilities = listOf("UWB precision finding", "Galaxy network"),
                privacyConcerns = listOf("Movement tracking", "Stalking potential")
            )
            DeviceType.GENERIC_BLE_TRACKER -> DeviceTypeInfo(
                name = "BLE Tracker",
                shortDescription = "Bluetooth Tracker Device",
                fullDescription = "Generic Bluetooth tracker device detected.",
                capabilities = listOf("Bluetooth tracking"),
                privacyConcerns = listOf("Movement tracking", "Stalking potential")
            )
            // Traffic Enforcement
            DeviceType.SPEED_CAMERA -> DeviceTypeInfo(
                name = "Speed Camera",
                shortDescription = "Speed Enforcement Camera",
                fullDescription = "Automated speed enforcement camera that photographs speeding vehicles.",
                capabilities = listOf("Speed measurement", "License plate capture"),
                privacyConcerns = listOf("Vehicle tracking", "Photo evidence stored")
            )
            DeviceType.RED_LIGHT_CAMERA -> DeviceTypeInfo(
                name = "Red Light Camera",
                shortDescription = "Intersection Enforcement Camera",
                fullDescription = "Camera that captures vehicles running red lights.",
                capabilities = listOf("Intersection monitoring", "License plate capture"),
                privacyConcerns = listOf("Intersection surveillance", "Data retention")
            )
            DeviceType.TOLL_READER -> DeviceTypeInfo(
                name = "Toll/E-ZPass Reader",
                shortDescription = "Electronic Toll Collection",
                fullDescription = "Electronic toll collection system that reads transponders and plates.",
                capabilities = listOf("Transponder reading", "License plate capture"),
                privacyConcerns = listOf("Travel pattern tracking", "Government data access")
            )
            DeviceType.TRAFFIC_SENSOR -> DeviceTypeInfo(
                name = "Traffic Sensor",
                shortDescription = "Traffic Monitoring Sensor",
                fullDescription = "Sensor for monitoring traffic flow and conditions.",
                capabilities = listOf("Vehicle counting", "Speed estimation"),
                privacyConcerns = listOf("Movement pattern data")
            )
            // Law Enforcement Specific
            DeviceType.SHOTSPOTTER -> DeviceTypeInfo(
                name = "ShotSpotter Sensor",
                shortDescription = "Gunshot Detection System",
                fullDescription = "ShotSpotter acoustic sensors detect gunfire and continuously monitor audio.",
                capabilities = listOf("Gunshot detection", "Audio triangulation"),
                privacyConcerns = listOf("Continuous audio surveillance", "May capture conversations", "False positive issues")
            )
            DeviceType.CLEARVIEW_AI -> DeviceTypeInfo(
                name = "Clearview AI System",
                shortDescription = "Facial Recognition Database",
                fullDescription = "Clearview AI scraped billions of photos to create a massive facial recognition database.",
                capabilities = listOf("Face matching from any photo", "Social media linking"),
                privacyConcerns = listOf("Scraped photos without consent", "Used by thousands of agencies", "No opt-out")
            )
            DeviceType.PALANTIR_DEVICE -> DeviceTypeInfo(
                name = "Palantir Device",
                shortDescription = "Data Analytics Platform",
                fullDescription = "Palantir provides data integration and analytics to law enforcement.",
                capabilities = listOf("Cross-database linking", "Pattern analysis"),
                privacyConcerns = listOf("Aggregates multiple data sources", "Predictive policing")
            )
            DeviceType.GRAYKEY_DEVICE -> DeviceTypeInfo(
                name = "GrayKey iPhone Forensics",
                shortDescription = "iPhone Passcode Cracking Device",
                fullDescription = "GrayKey (by Grayshift, founded by ex-Apple engineers) is specifically designed " +
                    "to bypass iPhone passcodes and extract data. It's one of the most powerful iPhone " +
                    "forensics tools available, capable of cracking even recent iOS versions.\n\n" +
                    "COST: $15,000-$30,000 per unit (exclusively sold to law enforcement)\n\n" +
                    "IF DETECTED NEARBY: This is HIGHLY UNUSUAL. GrayKey devices are expensive, rare, and " +
                    "typically only used in police forensics labs. Detection suggests active iPhone examination.",
                capabilities = listOf(
                    "Crack iPhone passcodes (4-digit to complex alphanumeric)",
                    "Works on recent iOS versions (with some delays)",
                    "BFU (Before First Unlock) extraction on some models",
                    "AFU (After First Unlock) full extraction",
                    "Extract: Messages, photos, call logs, app data",
                    "Access encrypted keychain data",
                    "Recover some deleted content",
                    "Faster than Cellebrite for iPhones in many cases"
                ),
                privacyConcerns = listOf(
                    "Can crack most iPhone passcodes given enough time",
                    "Law enforcement exclusive - indicates serious investigation",
                    "Newer iPhones with USB Restricted Mode are more resistant",
                    "Alphanumeric passwords take much longer to crack",
                    "Data extraction is comprehensive once unlocked"
                ),
                recommendations = listOf(
                    "Use long alphanumeric passcode (not 4/6 digit PIN)",
                    "Enable USB Restricted Mode (Settings > Face/Touch ID > Accessories)",
                    "Reboot phone before any law enforcement encounter",
                    "iPhone locked + BFU state is most secure",
                    "Consider device legal protections (5th Amendment)",
                    "Know that refusing to unlock may result in device seizure"
                )
            )
            // Network Surveillance
            DeviceType.WIFI_PINEAPPLE -> DeviceTypeInfo(
                name = "WiFi Pineapple",
                shortDescription = "Network Auditing/Attack Tool",
                fullDescription = "Hak5 WiFi Pineapple is used for network security testing but can be misused.",
                capabilities = listOf("Evil twin attacks", "Credential capture"),
                privacyConcerns = listOf("MITM attacks", "Password theft"),
                recommendations = listOf("Don't connect to unknown WiFi", "Use VPN")
            )
            DeviceType.PACKET_SNIFFER -> DeviceTypeInfo(
                name = "Packet Sniffer",
                shortDescription = "Network Traffic Analyzer",
                fullDescription = "Device capturing network traffic for analysis.",
                capabilities = listOf("Traffic capture", "Protocol analysis"),
                privacyConcerns = listOf("Captures unencrypted data", "Password interception")
            )
            DeviceType.MAN_IN_MIDDLE -> DeviceTypeInfo(
                name = "MITM Device",
                shortDescription = "Man-in-the-Middle Attack Device",
                fullDescription = "Device positioned between user and network to intercept communications.",
                capabilities = listOf("Traffic interception", "SSL stripping"),
                privacyConcerns = listOf("All traffic exposed", "Identity theft risk")
            )
            // ==================== Flipper Zero and Hacking Tools ====================
            DeviceType.FLIPPER_ZERO -> DeviceTypeInfo(
                name = "Flipper Zero",
                shortDescription = "Multi-Tool Hacking Device",
                fullDescription = "Flipper Zero is a portable multi-tool device designed for hardware hacking, " +
                    "pentesting, and interacting with access control systems. It combines multiple radio protocols " +
                    "and can read, clone, and emulate various types of wireless signals. While it has many legitimate " +
                    "uses for security research and education, it can also be misused for malicious purposes.\n\n" +
                    "FIRMWARE VARIANTS:\n" +
                    "- Official: Standard features with regional restrictions\n" +
                    "- Unleashed: Removes region locks on Sub-GHz\n" +
                    "- RogueMaster: More aggressive features\n" +
                    "- Xtreme/Momentum: Feature-packed custom firmware",
                capabilities = listOf(
                    "Sub-GHz (300-928 MHz): Garage doors, car key fobs, wireless sensors",
                    "RFID (125 kHz): EM4100, HID Prox access cards",
                    "NFC (13.56 MHz): Mifare, NTAG, EMV payment cards (read-only)",
                    "Infrared: TV remotes, AC units, appliances",
                    "iButton: 1-Wire devices, building access",
                    "GPIO: Hardware debugging and hacking",
                    "BadUSB: Keystroke injection via USB",
                    "BadBT: Bluetooth keystroke injection",
                    "BLE: Device impersonation, spam attacks"
                ),
                privacyConcerns = listOf(
                    "Can clone access cards (RFID/NFC)",
                    "Can capture and replay garage door signals",
                    "BadUSB can execute malicious scripts on unlocked computers",
                    "BLE spam can disrupt iOS/Android devices",
                    "Can be used for stalking via car key relay attacks",
                    "Presence may indicate targeted hacking attempt"
                ),
                recommendations = listOf(
                    "Context matters: Security conferences = expected, random public place = concerning",
                    "If experiencing device popups/crashes, check if a Flipper is nearby",
                    "Look for small orange/black device with LCD screen and D-pad",
                    "If your garage/car is affected, consider rolling code upgrades",
                    "Document detection time/location for pattern analysis",
                    "Flipper has limited range (~10-50m depending on attack)"
                )
            )
            DeviceType.FLIPPER_ZERO_SPAM -> DeviceTypeInfo(
                name = "Flipper Zero BLE Spam Attack",
                shortDescription = "Active BLE Spam Detected",
                fullDescription = "An active Bluetooth Low Energy spam attack has been detected, likely from a " +
                    "Flipper Zero device. This attack floods the BLE spectrum with fake device advertisements, " +
                    "causing popup floods on iPhones (Apple device pairing requests) or notification spam on " +
                    "Android (Fast Pair requests).\n\n" +
                    "This is MALICIOUS use of a Flipper Zero - there is no legitimate reason to spam BLE.",
                capabilities = listOf(
                    "iOS Popup Attack: Floods with fake AirPods/Apple device broadcasts",
                    "Android Fast Pair Spam: Floods with fake Google Fast Pair advertisements",
                    "Can crash older iOS versions",
                    "Can make devices unusable due to constant popups",
                    "Used for harassment or as distraction for other attacks"
                ),
                privacyConcerns = listOf(
                    "Active attack in progress",
                    "May be cover for other malicious activity",
                    "Indicates hostile intent",
                    "Person may be targeting you specifically"
                ),
                recommendations = listOf(
                    "IMMEDIATE: Turn off Bluetooth to stop popups",
                    "Look for person with small device (orange/black, LCD screen)",
                    "Move away from the area",
                    "If attack follows you, document and report to authorities",
                    "Note time/location for pattern analysis",
                    "Check if attacks stop when specific person leaves"
                )
            )
            DeviceType.HACKRF_SDR -> DeviceTypeInfo(
                name = "Software Defined Radio (HackRF/SDR)",
                shortDescription = "RF Analysis Device",
                fullDescription = "A Software Defined Radio (SDR) device capable of receiving and transmitting " +
                    "across a wide range of radio frequencies. HackRF One can cover 1 MHz to 6 GHz. " +
                    "Used for RF research, amateur radio, and security testing.",
                capabilities = listOf(
                    "Wide frequency range reception (1 MHz - 6 GHz)",
                    "Transmit capability on HackRF",
                    "Spectrum analysis",
                    "Protocol decoding",
                    "GPS spoofing (illegal)",
                    "Cellular signal analysis"
                ),
                privacyConcerns = listOf(
                    "Can intercept unencrypted RF signals",
                    "Can analyze your wireless transmissions",
                    "Transmit mode can jam/spoof signals",
                    "May be recording RF environment"
                ),
                recommendations = listOf(
                    "SDRs are common among radio hobbyists",
                    "Presence alone is not concerning",
                    "Be cautious if combined with other suspicious behavior",
                    "If experiencing GPS issues, SDR spoofing is possible"
                )
            )
            DeviceType.PROXMARK -> DeviceTypeInfo(
                name = "Proxmark RFID/NFC Tool",
                shortDescription = "RFID/NFC Cloning Device",
                fullDescription = "Proxmark is a powerful RFID/NFC research tool that can read, write, " +
                    "and emulate various card types. It's the gold standard for RFID security research " +
                    "but can be misused to clone access cards.",
                capabilities = listOf(
                    "Read/write 125 kHz RFID cards (EM4100, HID Prox)",
                    "Read/write 13.56 MHz NFC cards (Mifare, iClass)",
                    "Emulate cards in real-time",
                    "Sniff card-reader communications",
                    "Brute force weak card encryption"
                ),
                privacyConcerns = listOf(
                    "Can clone building access cards",
                    "Can read cards in your wallet/pocket",
                    "May be attempting unauthorized access",
                    "Often used by physical pentesters"
                ),
                recommendations = listOf(
                    "Common at security conferences",
                    "Unusual in random public places",
                    "Use RFID-blocking wallet if concerned",
                    "Report if seen near secure facilities"
                )
            )
            DeviceType.USB_RUBBER_DUCKY -> DeviceTypeInfo(
                name = "USB Rubber Ducky",
                shortDescription = "Keystroke Injection Device",
                fullDescription = "The USB Rubber Ducky looks like a USB flash drive but acts as a keyboard, " +
                    "typing pre-programmed keystrokes at superhuman speed. It can execute complex attacks " +
                    "in seconds on an unlocked computer.",
                capabilities = listOf(
                    "Keystroke injection at 1000+ characters/second",
                    "Can open shells, download malware, exfiltrate data",
                    "Works on any OS that accepts USB keyboards",
                    "New versions have WiFi and storage"
                ),
                privacyConcerns = listOf(
                    "Requires physical access to computer",
                    "Attack happens in seconds",
                    "Difficult to detect during execution"
                ),
                recommendations = listOf(
                    "Never leave computer unlocked",
                    "Be suspicious of 'found' USB drives",
                    "USB port locks can prevent attacks",
                    "Group Policy can restrict USB devices"
                )
            )
            DeviceType.BASH_BUNNY -> DeviceTypeInfo(
                name = "Bash Bunny",
                shortDescription = "USB Attack Platform",
                fullDescription = "Hak5 Bash Bunny is an advanced USB attack platform that can emulate " +
                    "multiple device types (keyboard, storage, ethernet) and run complex payloads.",
                capabilities = listOf(
                    "Multi-device emulation (keyboard, storage, ethernet)",
                    "Runs Debian Linux internally",
                    "Can exfiltrate files to internal storage",
                    "Network attacks via ethernet emulation",
                    "Credential harvesting"
                ),
                privacyConcerns = listOf(
                    "More powerful than Rubber Ducky",
                    "Can steal files and credentials",
                    "Network man-in-the-middle capability"
                ),
                recommendations = listOf(
                    "Same protections as USB Rubber Ducky",
                    "Monitor for new network adapters",
                    "Physical security is key"
                )
            )
            DeviceType.LAN_TURTLE -> DeviceTypeInfo(
                name = "LAN Turtle",
                shortDescription = "Covert Network Access Device",
                fullDescription = "Hak5 LAN Turtle is a covert network access device disguised as a USB ethernet adapter. " +
                    "It provides persistent remote access to networks.",
                capabilities = listOf(
                    "Appears as normal USB ethernet adapter",
                    "Provides remote shell access",
                    "Man-in-the-middle network position",
                    "DNS spoofing and credential capture",
                    "VPN tunneling out of network"
                ),
                privacyConcerns = listOf(
                    "Hard to detect (looks like normal adapter)",
                    "Provides persistent access",
                    "All your network traffic may be monitored"
                ),
                recommendations = listOf(
                    "Check for unknown USB devices on computers",
                    "Monitor network for unauthorized devices",
                    "Use encrypted connections (HTTPS, VPN)"
                )
            )
            DeviceType.KEYCROC -> DeviceTypeInfo(
                name = "Key Croc",
                shortDescription = "Keylogger with Exfiltration",
                fullDescription = "Hak5 Key Croc is an inline keylogger that sits between keyboard and computer, " +
                    "capturing all keystrokes and exfiltrating them over WiFi.",
                capabilities = listOf(
                    "Captures all keystrokes",
                    "WiFi exfiltration of captured data",
                    "Trigger-based payload execution",
                    "Pattern matching for credentials"
                ),
                privacyConcerns = listOf(
                    "Captures all passwords typed",
                    "Hard to detect (inline device)",
                    "Real-time exfiltration capability"
                ),
                recommendations = listOf(
                    "Visually inspect keyboard connection",
                    "Use password managers (paste instead of type)",
                    "Check for inline devices regularly"
                )
            )
            DeviceType.SHARK_JACK -> DeviceTypeInfo(
                name = "Shark Jack",
                shortDescription = "Portable Network Attack Tool",
                fullDescription = "Hak5 Shark Jack is a portable network attack and reconnaissance tool " +
                    "that fits in your pocket.",
                capabilities = listOf(
                    "Network reconnaissance",
                    "Automated attack payloads",
                    "Nmap scanning",
                    "Data exfiltration"
                ),
                privacyConcerns = listOf(
                    "Can scan and attack networks quickly",
                    "Automated reconnaissance",
                    "Portable and concealable"
                ),
                recommendations = listOf(
                    "Monitor for port scanning",
                    "Network access control",
                    "802.1X authentication"
                )
            )
            DeviceType.SCREEN_CRAB -> DeviceTypeInfo(
                name = "Screen Crab",
                shortDescription = "HDMI Man-in-the-Middle",
                fullDescription = "Hak5 Screen Crab intercepts HDMI video streams, capturing screenshots " +
                    "and exfiltrating them over WiFi.",
                capabilities = listOf(
                    "HDMI video interception",
                    "Screenshot capture",
                    "WiFi exfiltration",
                    "Remote viewing capability"
                ),
                privacyConcerns = listOf(
                    "Captures everything on screen",
                    "Passwords visible when typed",
                    "Sensitive documents exposed"
                ),
                recommendations = listOf(
                    "Check HDMI connections",
                    "Look for inline devices",
                    "Use encrypted screen content where possible"
                )
            )
            DeviceType.GENERIC_HACKING_TOOL -> DeviceTypeInfo(
                name = "Security Testing Tool",
                shortDescription = "Potential Hacking Device",
                fullDescription = "A device matching patterns associated with security testing and hacking tools " +
                    "has been detected. This could be legitimate security research or potentially malicious activity.",
                capabilities = listOf(
                    "Varies by specific device",
                    "May include wireless attacks",
                    "May include physical access attacks"
                ),
                privacyConcerns = listOf(
                    "Purpose unknown without context",
                    "Could be legitimate or malicious",
                    "Monitor for suspicious behavior"
                ),
                recommendations = listOf(
                    "Consider the context (security conference vs random location)",
                    "Watch for correlated suspicious activity",
                    "Document if concerned"
                )
            )
            // Misc Surveillance
            DeviceType.LICENSE_PLATE_READER -> DeviceTypeInfo(
                name = "License Plate Reader",
                shortDescription = "ALPR Camera",
                fullDescription = "Automated License Plate Reader camera system.",
                capabilities = listOf("Plate capture", "Vehicle identification"),
                privacyConcerns = listOf("Mass vehicle tracking", "Location history")
            )
            DeviceType.CCTV_CAMERA -> DeviceTypeInfo(
                name = "CCTV Camera",
                shortDescription = "Closed-Circuit Camera",
                fullDescription = "Standard surveillance camera system.",
                capabilities = listOf("Video recording", "Motion detection"),
                privacyConcerns = listOf("Continuous monitoring", "Recording retention")
            )
            DeviceType.PTZ_CAMERA -> DeviceTypeInfo(
                name = "PTZ Camera",
                shortDescription = "Pan-Tilt-Zoom Camera",
                fullDescription = "Camera with remote pan, tilt, and zoom capabilities for active surveillance.",
                capabilities = listOf("Remote directional control", "Optical zoom"),
                privacyConcerns = listOf("Active tracking capability")
            )
            DeviceType.THERMAL_CAMERA -> DeviceTypeInfo(
                name = "Thermal Camera",
                shortDescription = "Infrared/Thermal Imaging",
                fullDescription = "Camera using thermal imaging to detect heat signatures.",
                capabilities = listOf("Heat detection", "Night operation"),
                privacyConcerns = listOf("Sees through concealment", "Activity detection in private spaces")
            )
            DeviceType.NIGHT_VISION -> DeviceTypeInfo(
                name = "Night Vision Device",
                shortDescription = "Low-Light Imaging",
                fullDescription = "Device using infrared or image intensification for night surveillance.",
                capabilities = listOf("Low-light operation"),
                privacyConcerns = listOf("Surveillance in darkness")
            )
        }
    }
    
    data class DeviceTypeInfo(
        val name: String,
        val shortDescription: String,
        val fullDescription: String,
        val capabilities: List<String>,
        val privacyConcerns: List<String>,
        val recommendations: List<String> = emptyList()
    )
    
    // ==================== OUI Manufacturer Lookup ====================
    // Common manufacturer OUIs for quick identification
    private val ouiManufacturers = mapOf(
        "00:00:0C" to "Cisco",
        "00:01:42" to "Cisco",
        "00:0C:29" to "VMware",
        "00:0D:3A" to "Microsoft",
        "00:14:22" to "Dell",
        "00:17:88" to "Philips",
        "00:1A:11" to "Google",
        "00:1E:C2" to "Apple",
        "00:23:32" to "Apple",
        "00:25:00" to "Apple",
        "00:26:BB" to "Apple",
        "00:50:56" to "VMware",
        "08:00:27" to "Oracle VirtualBox",
        "14:13:46" to "Xiaomi",
        "18:65:90" to "Apple",
        "28:6A:B8" to "Apple",
        "2C:BE:08" to "Apple",
        "34:23:BA" to "Xiaomi",
        "38:F9:D3" to "Apple",
        "3C:06:30" to "Apple",
        "40:4E:36" to "HP",
        "44:D9:E7" to "Ubiquiti",
        "50:29:4D" to "Quectel",
        "54:60:09" to "Google",
        "58:CB:52" to "Google",
        "5C:CF:7F" to "Espressif",
        "60:01:94" to "Espressif",
        "70:B3:D5" to "IEEE Registration",
        "78:4F:43" to "Apple",
        "80:6D:97" to "Samsung",
        "84:D8:1B" to "Apple",
        "88:E9:FE" to "Apple",
        "8C:85:90" to "Apple",
        "94:65:2D" to "OnePlus",
        "98:D6:F7" to "LG",
        "9C:8E:99" to "Hewlett Packard",
        "A4:77:33" to "Google",
        "A4:C6:39" to "Intel",
        "AC:37:43" to "HTC",
        "B0:34:95" to "Apple",
        "B4:F1:DA" to "LG",
        "B8:27:EB" to "Raspberry Pi",
        "BC:83:85" to "Microsoft",
        "C0:EE:FB" to "OnePlus",
        "C8:3D:D4" to "CyberTAN",
        "CC:46:D6" to "Cisco",
        "D0:03:4B" to "Apple",
        "D4:61:9D" to "Apple",
        "D4:CA:6E" to "u-blox",
        "D8:C7:71" to "Telit",
        "DC:A6:32" to "Raspberry Pi",
        "E0:5F:45" to "Apple",
        "E4:5F:01" to "Raspberry Pi",
        "EC:85:2F" to "Apple",
        "F0:18:98" to "Apple",
        "F4:F5:D8" to "Google",
        "F8:1A:67" to "TP-Link",
        "FC:A1:3E" to "Samsung"
    )
    
    /**
     * Try to identify manufacturer from MAC OUI (first 3 octets)
     */
    fun getManufacturerFromOui(oui: String): String? {
        val normalizedOui = oui.uppercase().replace("-", ":").take(8)
        return ouiManufacturers[normalizedOui] ?: macPrefixes.find {
            normalizedOui.startsWith(it.prefix.uppercase())
        }?.manufacturer
    }

    // ==================== UNKNOWN DEVICE ANALYSIS ====================

    /**
     * Comprehensive analysis result for an unknown BLE device.
     */
    data class UnknownDeviceAnalysis(
        val macAddress: String,
        val manufacturerFromOui: String?,
        val manufacturerCategory: ManufacturerCategory,
        val serviceUuidAnalysis: ServiceUuidAnalysis,
        val advertisingBehavior: AdvertisingBehavior,
        val signalCharacteristics: SignalCharacteristics,
        val classificationConfidence: Float,
        val suggestedDeviceType: DeviceType?,
        val threatAssessment: String,
        val investigationPriority: InvestigationPriority
    )

    /**
     * Categories of manufacturers for quick risk assessment.
     */
    enum class ManufacturerCategory(val riskLevel: Int, val description: String) {
        CONSUMER_ELECTRONICS(1, "Major consumer electronics (Apple, Samsung, Google)"),
        IOT_CHIPMAKER(2, "IoT chip manufacturers (Espressif, Nordic, TI)"),
        TELECOM_MODEM(3, "Cellular/LTE modem makers (Quectel, Telit, Sierra)"),
        NETWORKING(2, "Networking equipment (Cisco, Ubiquiti, TP-Link)"),
        LAW_ENFORCEMENT(5, "Known law enforcement suppliers"),
        SURVEILLANCE(5, "Surveillance equipment manufacturers"),
        UNKNOWN(3, "Unknown manufacturer - requires investigation")
    }

    /**
     * Service UUID analysis for unknown devices.
     */
    data class ServiceUuidAnalysis(
        val totalUuids: Int,
        val standardUuids: List<StandardUuidInfo>,
        val customUuids: List<String>,
        val suspiciousPatterns: List<String>
    )

    /**
     * Information about a standard BLE service UUID.
     */
    data class StandardUuidInfo(
        val uuid: String,
        val name: String,
        val description: String,
        val commonUsage: String
    )

    /**
     * Advertising behavior analysis.
     */
    data class AdvertisingBehavior(
        val advertisingRate: Float,
        val rateCategory: RateCategory,
        val isConsistent: Boolean,
        val behavioralNotes: List<String>
    )

    /**
     * Advertising rate categories.
     */
    enum class RateCategory(val description: String) {
        VERY_LOW("< 0.5 pps - power saving mode or beacon"),
        NORMAL("0.5-2 pps - typical BLE device"),
        ELEVATED("2-10 pps - active device or tracking"),
        HIGH("10-20 pps - aggressive advertising"),
        SPIKE("> 20 pps - activation event or attack")
    }

    /**
     * Signal characteristics analysis.
     */
    data class SignalCharacteristics(
        val rssi: Int,
        val estimatedDistance: String,
        val proximityCategory: ProximityCategory
    )

    /**
     * Proximity categories based on RSSI.
     */
    enum class ProximityCategory(val description: String) {
        IMMEDIATE("On your person or in direct contact"),
        NEAR("Within a few meters - same room"),
        MEDIUM("Within 10-20 meters - nearby"),
        FAR("Beyond 20 meters - could be incidental"),
        EDGE("At detection limit - may be unreliable")
    }

    /**
     * Investigation priority for unknown devices.
     */
    enum class InvestigationPriority(val urgency: Int, val action: String) {
        CRITICAL(4, "Investigate immediately - potential active surveillance"),
        HIGH(3, "Investigate soon - suspicious characteristics"),
        MEDIUM(2, "Monitor over time - gather more data"),
        LOW(1, "Note and continue - likely benign"),
        IGNORE(0, "No action needed - clearly benign")
    }

    // Standard BLE service UUIDs for identification
    private val standardServiceUuids = mapOf(
        "1800" to StandardUuidInfo("1800", "Generic Access", "Basic device info", "All BLE devices"),
        "1801" to StandardUuidInfo("1801", "Generic Attribute", "GATT service discovery", "All BLE devices"),
        "180A" to StandardUuidInfo("180A", "Device Information", "Manufacturer, model, serial", "All BLE devices"),
        "180F" to StandardUuidInfo("180F", "Battery Service", "Battery level", "Consumer devices"),
        "1809" to StandardUuidInfo("1809", "Health Thermometer", "Temperature readings", "Medical/health devices"),
        "1819" to StandardUuidInfo("1819", "Location and Navigation", "GPS/location data", "Fitness/tracking"),
        "FD5A" to StandardUuidInfo("FD5A", "Samsung SmartTag", "Samsung tracker", "Samsung trackers"),
        "FEED" to StandardUuidInfo("FEED", "Tile Tracker", "Tile service", "Tile trackers"),
        "7DFC9000" to StandardUuidInfo("7DFC9000", "Apple Find My", "Find My network", "AirTags, Find My devices"),
        "FE9F" to StandardUuidInfo("FE9F", "Google Fast Pair", "Quick pairing", "Android devices"),
        "FEAA" to StandardUuidInfo("FEAA", "Eddystone", "Google beacon", "Retail/location beacons"),
        "0000" to StandardUuidInfo("0000", "Generic Service", "Custom implementation", "Various")
    )

    /**
     * Analyze an unknown BLE device comprehensively.
     */
    fun analyzeUnknownDevice(
        macAddress: String,
        deviceName: String?,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String>,
        rssi: Int,
        advertisingRate: Float
    ): UnknownDeviceAnalysis {
        // Get manufacturer from OUI
        val normalizedMac = macAddress.uppercase().replace("-", ":")
        val oui = normalizedMac.take(8)
        val manufacturer = getManufacturerFromOui(oui)

        // Categorize manufacturer
        val manufacturerCategory = categorizeManufacturer(manufacturer, manufacturerData)

        // Analyze service UUIDs
        val serviceUuidAnalysis = analyzeServiceUuids(serviceUuids)

        // Analyze advertising behavior
        val advertisingBehavior = analyzeAdvertisingBehavior(advertisingRate)

        // Analyze signal characteristics
        val signalCharacteristics = analyzeSignalCharacteristics(rssi)

        // Calculate classification confidence
        val classificationConfidence = calculateClassificationConfidence(
            manufacturer, deviceName, serviceUuids, manufacturerData
        )

        // Suggest device type
        val suggestedDeviceType = suggestDeviceType(
            manufacturer, deviceName, serviceUuids, manufacturerData, advertisingRate
        )

        // Build threat assessment
        val threatAssessment = buildThreatAssessment(
            manufacturerCategory, serviceUuidAnalysis, advertisingBehavior,
            signalCharacteristics, suggestedDeviceType
        )

        // Determine investigation priority
        val investigationPriority = determineInvestigationPriority(
            manufacturerCategory, serviceUuidAnalysis, advertisingBehavior,
            signalCharacteristics, classificationConfidence
        )

        return UnknownDeviceAnalysis(
            macAddress = macAddress,
            manufacturerFromOui = manufacturer,
            manufacturerCategory = manufacturerCategory,
            serviceUuidAnalysis = serviceUuidAnalysis,
            advertisingBehavior = advertisingBehavior,
            signalCharacteristics = signalCharacteristics,
            classificationConfidence = classificationConfidence,
            suggestedDeviceType = suggestedDeviceType,
            threatAssessment = threatAssessment,
            investigationPriority = investigationPriority
        )
    }

    private fun categorizeManufacturer(manufacturer: String?, manufacturerData: Map<Int, String>): ManufacturerCategory {
        // Check manufacturer data IDs first
        if (manufacturerData.containsKey(0x004C)) return ManufacturerCategory.CONSUMER_ELECTRONICS // Apple
        if (manufacturerData.containsKey(0x0075)) return ManufacturerCategory.CONSUMER_ELECTRONICS // Samsung
        if (manufacturerData.containsKey(0x00E0)) return ManufacturerCategory.CONSUMER_ELECTRONICS // Google
        if (manufacturerData.containsKey(0x0059)) return ManufacturerCategory.IOT_CHIPMAKER // Nordic

        return when (manufacturer?.lowercase()) {
            "apple", "samsung", "google", "lg", "oneplus", "htc", "xiaomi" ->
                ManufacturerCategory.CONSUMER_ELECTRONICS
            "espressif", "nordic semiconductor", "texas instruments", "dialog semiconductor" ->
                ManufacturerCategory.IOT_CHIPMAKER
            "quectel", "telit", "sierra wireless", "u-blox" ->
                ManufacturerCategory.TELECOM_MODEM
            "cisco", "ubiquiti", "tp-link", "cradlepoint", "digi international" ->
                ManufacturerCategory.NETWORKING
            "axon", "motorola solutions", "l3harris", "digital ally" ->
                ManufacturerCategory.LAW_ENFORCEMENT
            "flock safety", "soundthinking", "shotspotter" ->
                ManufacturerCategory.SURVEILLANCE
            null -> ManufacturerCategory.UNKNOWN
            else -> ManufacturerCategory.UNKNOWN
        }
    }

    private fun analyzeServiceUuids(serviceUuids: List<java.util.UUID>): ServiceUuidAnalysis {
        val standardUuidInfos = mutableListOf<StandardUuidInfo>()
        val customUuidStrings = mutableListOf<String>()
        val suspiciousPatterns = mutableListOf<String>()

        for (uuid in serviceUuids) {
            val uuidStr = uuid.toString().uppercase()
            val shortForm = uuidStr.substring(4, 8)

            // Check for standard UUID
            val standardInfo = standardServiceUuids[shortForm]
            if (standardInfo != null) {
                standardUuidInfos.add(standardInfo)

                // Check for suspicious standard services
                when (shortForm) {
                    "1819" -> suspiciousPatterns.add("Location/Navigation service - device may track position")
                    "1809" -> suspiciousPatterns.add("Health Thermometer - may be repurposed for data exfiltration")
                }
            } else {
                customUuidStrings.add(uuidStr)

                // Check for known suspicious patterns
                if (uuidStr.startsWith("00003")) {
                    suspiciousPatterns.add("Raven-like custom service UUID detected: $shortForm")
                }
                if (uuidStr.contains("7DFC9000", ignoreCase = true)) {
                    suspiciousPatterns.add("Apple Find My network service detected")
                }
            }
        }

        // Check for suspicious combinations
        if (serviceUuids.size > 5) {
            suspiciousPatterns.add("Unusually high number of services (${serviceUuids.size}) - may indicate complex device")
        }

        return ServiceUuidAnalysis(
            totalUuids = serviceUuids.size,
            standardUuids = standardUuidInfos,
            customUuids = customUuidStrings,
            suspiciousPatterns = suspiciousPatterns
        )
    }

    private fun analyzeAdvertisingBehavior(advertisingRate: Float): AdvertisingBehavior {
        val rateCategory = when {
            advertisingRate < 0.5f -> RateCategory.VERY_LOW
            advertisingRate < 2f -> RateCategory.NORMAL
            advertisingRate < 10f -> RateCategory.ELEVATED
            advertisingRate < 20f -> RateCategory.HIGH
            else -> RateCategory.SPIKE
        }

        val behavioralNotes = mutableListOf<String>()
        when (rateCategory) {
            RateCategory.VERY_LOW -> behavioralNotes.add("Low power mode - beacon or sleeping device")
            RateCategory.NORMAL -> behavioralNotes.add("Standard BLE advertising - typical consumer device")
            RateCategory.ELEVATED -> behavioralNotes.add("Elevated rate - active communication or tracking")
            RateCategory.HIGH -> behavioralNotes.add("High rate - aggressive advertising, potential tracker")
            RateCategory.SPIKE -> behavioralNotes.add("SPIKE DETECTED - possible activation event or attack")
        }

        return AdvertisingBehavior(
            advertisingRate = advertisingRate,
            rateCategory = rateCategory,
            isConsistent = true, // Would need historical data to determine
            behavioralNotes = behavioralNotes
        )
    }

    private fun analyzeSignalCharacteristics(rssi: Int): SignalCharacteristics {
        val estimatedDistance = when {
            rssi > -40 -> "< 1m (direct contact)"
            rssi > -50 -> "1-3m (very close)"
            rssi > -60 -> "3-10m (same room)"
            rssi > -70 -> "10-20m (nearby)"
            rssi > -80 -> "20-50m (medium distance)"
            else -> "> 50m (far/unreliable)"
        }

        val proximityCategory = when {
            rssi > -45 -> ProximityCategory.IMMEDIATE
            rssi > -55 -> ProximityCategory.NEAR
            rssi > -70 -> ProximityCategory.MEDIUM
            rssi > -85 -> ProximityCategory.FAR
            else -> ProximityCategory.EDGE
        }

        return SignalCharacteristics(
            rssi = rssi,
            estimatedDistance = estimatedDistance,
            proximityCategory = proximityCategory
        )
    }

    private fun calculateClassificationConfidence(
        manufacturer: String?,
        deviceName: String?,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String>
    ): Float {
        var confidence = 0.2f // Base confidence for unknown

        if (manufacturer != null) confidence += 0.2f
        if (deviceName != null && deviceName.isNotBlank()) confidence += 0.25f
        if (serviceUuids.isNotEmpty()) confidence += 0.15f
        if (manufacturerData.isNotEmpty()) confidence += 0.2f

        return confidence.coerceIn(0f, 1f)
    }

    private fun suggestDeviceType(
        manufacturer: String?,
        deviceName: String?,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String>,
        advertisingRate: Float
    ): DeviceType? {
        // Check manufacturer data for known trackers
        if (manufacturerData.containsKey(0x004C)) {
            // Apple - check for AirTag patterns
            val data = manufacturerData[0x004C] ?: ""
            if (data.startsWith("12") || data.startsWith("07")) {
                return DeviceType.AIRTAG
            }
        }
        if (manufacturerData.containsKey(0x00C7)) return DeviceType.TILE_TRACKER
        if (manufacturerData.containsKey(0x0075)) return DeviceType.SAMSUNG_SMARTTAG

        // Check service UUIDs
        for (uuid in serviceUuids) {
            val shortForm = uuid.toString().uppercase().substring(4, 8)
            when {
                shortForm == "FD5A" -> return DeviceType.SAMSUNG_SMARTTAG
                shortForm.startsWith("FEED") -> return DeviceType.TILE_TRACKER
            }
        }

        // Check device name patterns
        deviceName?.let { name ->
            matchBleNamePattern(name)?.let { return it.deviceType }
        }

        // High advertising rate suggests activation
        if (advertisingRate > 20f) {
            if (manufacturerData.containsKey(0x0059)) { // Nordic
                return DeviceType.AXON_POLICE_TECH
            }
        }

        return null
    }

    private fun buildThreatAssessment(
        manufacturerCategory: ManufacturerCategory,
        serviceUuidAnalysis: ServiceUuidAnalysis,
        advertisingBehavior: AdvertisingBehavior,
        signalCharacteristics: SignalCharacteristics,
        suggestedDeviceType: DeviceType?
    ): String {
        val assessmentParts = mutableListOf<String>()

        // Manufacturer assessment
        when (manufacturerCategory) {
            ManufacturerCategory.LAW_ENFORCEMENT,
            ManufacturerCategory.SURVEILLANCE -> {
                assessmentParts.add("HIGH RISK: Manufacturer associated with surveillance equipment")
            }
            ManufacturerCategory.TELECOM_MODEM -> {
                assessmentParts.add("MODERATE RISK: LTE modem chip - used in IoT surveillance devices")
            }
            ManufacturerCategory.IOT_CHIPMAKER -> {
                assessmentParts.add("MODERATE RISK: IoT chipmaker - used in various devices including trackers")
            }
            ManufacturerCategory.UNKNOWN -> {
                assessmentParts.add("UNKNOWN RISK: Cannot identify manufacturer")
            }
            else -> {
                assessmentParts.add("LOW RISK: Common consumer electronics manufacturer")
            }
        }

        // Service UUID assessment
        if (serviceUuidAnalysis.suspiciousPatterns.isNotEmpty()) {
            assessmentParts.add("SUSPICIOUS: ${serviceUuidAnalysis.suspiciousPatterns.joinToString("; ")}")
        }

        // Advertising behavior assessment
        if (advertisingBehavior.rateCategory in listOf(RateCategory.HIGH, RateCategory.SPIKE)) {
            assessmentParts.add("WARNING: Abnormal advertising rate detected")
        }

        // Proximity assessment
        if (signalCharacteristics.proximityCategory == ProximityCategory.IMMEDIATE) {
            assessmentParts.add("PROXIMITY: Device is very close - check your belongings")
        }

        return assessmentParts.joinToString("\n")
    }

    private fun determineInvestigationPriority(
        manufacturerCategory: ManufacturerCategory,
        serviceUuidAnalysis: ServiceUuidAnalysis,
        advertisingBehavior: AdvertisingBehavior,
        signalCharacteristics: SignalCharacteristics,
        classificationConfidence: Float
    ): InvestigationPriority {
        var score = 0

        // Manufacturer risk
        score += manufacturerCategory.riskLevel

        // Suspicious patterns
        score += serviceUuidAnalysis.suspiciousPatterns.size

        // Advertising behavior
        when (advertisingBehavior.rateCategory) {
            RateCategory.HIGH -> score += 2
            RateCategory.SPIKE -> score += 4
            else -> {}
        }

        // Proximity
        when (signalCharacteristics.proximityCategory) {
            ProximityCategory.IMMEDIATE -> score += 3
            ProximityCategory.NEAR -> score += 2
            else -> {}
        }

        // Low confidence = more investigation needed
        if (classificationConfidence < 0.5f) score += 1

        return when {
            score >= 10 -> InvestigationPriority.CRITICAL
            score >= 7 -> InvestigationPriority.HIGH
            score >= 4 -> InvestigationPriority.MEDIUM
            score >= 2 -> InvestigationPriority.LOW
            else -> InvestigationPriority.IGNORE
        }
    }

    /**
     * Get all built-in patterns as SurveillancePattern objects.
     * Used for merging with custom/downloaded patterns.
     */
    val allPatterns: List<SurveillancePattern> by lazy {
        val patterns = mutableListOf<SurveillancePattern>()

        // Group SSID patterns by device type
        val ssidByDevice = ssidPatterns.groupBy { it.deviceType }
        ssidByDevice.forEach { (deviceType, detectionPatterns) ->
            patterns.add(
                SurveillancePattern(
                    id = "builtin_ssid_${deviceType.name.lowercase()}",
                    name = deviceType.displayName,
                    description = detectionPatterns.first().description,
                    deviceType = deviceType,
                    manufacturer = detectionPatterns.first().manufacturer,
                    threatLevel = scoreToThreatLevel(detectionPatterns.maxOf { it.threatScore }),
                    ssidPatterns = detectionPatterns.map { it.pattern },
                    isBuiltIn = true
                )
            )
        }

        // Group BLE name patterns by device type
        val bleByDevice = bleNamePatterns.groupBy { it.deviceType }
        bleByDevice.forEach { (deviceType, detectionPatterns) ->
            // Check if we already have an entry for this device
            val existingIndex = patterns.indexOfFirst { it.deviceType == deviceType }
            if (existingIndex >= 0) {
                // Merge BLE patterns into existing entry
                val existing = patterns[existingIndex]
                patterns[existingIndex] = existing.copy(
                    bleNamePatterns = detectionPatterns.map { it.pattern }
                )
            } else {
                patterns.add(
                    SurveillancePattern(
                        id = "builtin_ble_${deviceType.name.lowercase()}",
                        name = deviceType.displayName,
                        description = detectionPatterns.first().description,
                        deviceType = deviceType,
                        manufacturer = detectionPatterns.first().manufacturer,
                        threatLevel = scoreToThreatLevel(detectionPatterns.maxOf { it.threatScore }),
                        bleNamePatterns = detectionPatterns.map { it.pattern },
                        isBuiltIn = true
                    )
                )
            }
        }

        // Group MAC prefixes by device type
        val macByDevice = macPrefixes.groupBy { it.deviceType }
        macByDevice.forEach { (deviceType, macPrefixList) ->
            val existingIndex = patterns.indexOfFirst { it.deviceType == deviceType }
            if (existingIndex >= 0) {
                val existing = patterns[existingIndex]
                patterns[existingIndex] = existing.copy(
                    macPrefixes = macPrefixList.map { it.prefix }
                )
            } else {
                patterns.add(
                    SurveillancePattern(
                        id = "builtin_mac_${deviceType.name.lowercase()}",
                        name = deviceType.displayName,
                        description = macPrefixList.first().description,
                        deviceType = deviceType,
                        manufacturer = macPrefixList.first().manufacturer,
                        threatLevel = scoreToThreatLevel(macPrefixList.maxOf { it.threatScore }),
                        macPrefixes = macPrefixList.map { it.prefix },
                        isBuiltIn = true
                    )
                )
            }
        }

        patterns.toList()
    }

    // ==================== REAL-WORLD RF SURVEILLANCE KNOWLEDGE ====================
    // Comprehensive reference data for RF-based surveillance detection

    /**
     * Common surveillance RF frequencies and their typical uses.
     * Essential for understanding what devices operate on which bands.
     */
    object RfFrequencyReference {
        // ==================== Hidden Camera / Bug Detection ====================

        /** Older analog video transmitters - lower quality, easier to detect */
        const val FREQ_900_MHZ = 900_000_000L      // Analog video, old devices
        const val FREQ_1200_MHZ = 1_200_000_000L   // Video transmitters (1.2 GHz)
        const val FREQ_2400_MHZ = 2_400_000_000L   // WiFi cameras, cheap bugs, most IoT
        const val FREQ_5800_MHZ = 5_800_000_000L   // Higher quality video, FPV drones

        /** Remote controls and triggers */
        const val FREQ_315_MHZ = 315_000_000L      // US garage doors, remotes
        const val FREQ_433_MHZ = 433_920_000L      // EU remotes, cheap IoT, key fobs
        const val FREQ_868_MHZ = 868_000_000L      // EU ISM band, LoRa
        const val FREQ_915_MHZ = 915_000_000L      // US ISM band, Amazon Sidewalk

        /** GPS frequencies (for detecting jammers/spoofers) */
        const val GPS_L1_FREQ = 1_575_420_000L     // GPS L1 (primary civilian)
        const val GPS_L2_FREQ = 1_227_600_000L     // GPS L2
        const val GPS_L5_FREQ = 1_176_450_000L     // GPS L5 (newer)
        const val GLONASS_L1_FREQ = 1_602_000_000L // GLONASS
        const val GALILEO_E1_FREQ = 1_575_420_000L // Galileo E1

        /** Cellular bands (for IMSI catcher detection) */
        const val CELL_850_MHZ = 850_000_000L      // 2G/3G
        const val CELL_900_MHZ = 900_000_000L      // 2G/3G GSM
        const val CELL_1800_MHZ = 1_800_000_000L   // 2G DCS
        const val CELL_1900_MHZ = 1_900_000_000L   // 2G/3G PCS
        const val CELL_700_MHZ = 700_000_000L      // LTE Band 12/13/17
        const val CELL_2100_MHZ = 2_100_000_000L   // 3G UMTS
        const val CELL_2600_MHZ = 2_600_000_000L   // LTE Band 7

        val hiddenCameraBugFrequencies = listOf(
            FrequencyBand(900_000_000L, 50_000_000L, "900 MHz Analog Video",
                "Older analog devices - lower quality, easier to detect"),
            FrequencyBand(1_200_000_000L, 100_000_000L, "1.2 GHz Video Transmitters",
                "Higher quality analog video, moderate detection difficulty"),
            FrequencyBand(2_400_000_000L, 100_000_000L, "2.4 GHz WiFi/IoT",
                "Most cheap bugs, WiFi cameras, IoT devices - very common"),
            FrequencyBand(5_800_000_000L, 150_000_000L, "5.8 GHz High Quality",
                "Higher quality video, FPV systems, harder to detect")
        )

        val remoteControlFrequencies = listOf(
            FrequencyBand(315_000_000L, 5_000_000L, "315 MHz (US)",
                "US garage doors, car remotes, some trackers"),
            FrequencyBand(433_920_000L, 5_000_000L, "433 MHz (EU/US)",
                "EU remotes, cheap IoT, key fobs, many trackers")
        )

        data class FrequencyBand(
            val centerHz: Long,
            val bandwidthHz: Long,
            val name: String,
            val description: String
        ) {
            fun contains(frequency: Long): Boolean {
                val halfBandwidth = bandwidthHz / 2
                return frequency in (centerHz - halfBandwidth)..(centerHz + halfBandwidth)
            }

            val startHz: Long get() = centerHz - bandwidthHz / 2
            val endHz: Long get() = centerHz + bandwidthHz / 2

            fun formatRange(): String {
                val startMhz = startHz / 1_000_000.0
                val endMhz = endHz / 1_000_000.0
                return String.format("%.1f - %.1f MHz", startMhz, endMhz)
            }
        }
    }

    // ==================== GPS TRACKER PROFILES ====================

    /**
     * Comprehensive GPS tracker profiles based on real-world deployment patterns.
     * Includes OBD-II, magnetic, and hardwired trackers.
     */
    data class GpsTrackerProfile(
        val id: String,
        val name: String,
        val category: GpsTrackerCategory,
        val manufacturer: String?,
        val powerSource: PowerSource,
        val backhaul: BackhaulType,
        val typicalDeployment: List<String>,
        val physicalDescription: String,
        val detectionMethods: List<String>,
        val dataCollected: List<String>,
        val batteryLife: String?, // null for powered devices
        val commonLocations: List<String>,
        val legalStatus: String,
        val threatScore: Int
    )

    enum class GpsTrackerCategory(val displayName: String) {
        OBD_II_PORT("OBD-II Port Tracker"),
        MAGNETIC("Magnetic/Battery Tracker"),
        HARDWIRED("Hardwired Tracker"),
        PERSONAL("Personal/Asset Tracker"),
        FLEET("Fleet Management")
    }

    enum class PowerSource(val displayName: String) {
        OBD_VEHICLE_POWER("OBD-II Vehicle Power (always on)"),
        VEHICLE_HARDWIRE("Hardwired to Vehicle (always on)"),
        INTERNAL_BATTERY("Internal Battery"),
        BATTERY_WITH_SOLAR("Battery + Solar"),
        EXTERNAL_BATTERY_PACK("External Battery Pack")
    }

    enum class BackhaulType(val displayName: String) {
        CELLULAR_4G("4G LTE Cellular"),
        CELLULAR_3G("3G Cellular"),
        CELLULAR_2G("2G GSM (older)"),
        WIFI("WiFi (config only)"),
        BLUETOOTH("Bluetooth (proximity only)"),
        LORA("LoRa (long range, low power)"),
        SATELLITE("Satellite (Iridium/Globalstar)")
    }

    val gpsTrackerProfiles = listOf(
        // OBD-II Port Trackers
        GpsTrackerProfile(
            id = "bouncie",
            name = "Bouncie GPS Tracker",
            category = GpsTrackerCategory.OBD_II_PORT,
            manufacturer = "Bouncie",
            powerSource = PowerSource.OBD_VEHICLE_POWER,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Consumer vehicle tracking",
                "Teen driver monitoring",
                "Fleet management"
            ),
            physicalDescription = "Small OBD-II plug-in device, usually blue or black, ~2x3 inches",
            detectionMethods = listOf(
                "Check OBD-II port under dashboard (driver's side)",
                "Look for small device plugged into diagnostic port",
                "Use OBD-II scanner to detect unknown device",
                "Check for cellular signal near OBD port area"
            ),
            dataCollected = listOf(
                "GPS location (real-time)",
                "Vehicle speed",
                "Trip history",
                "Rapid acceleration/braking events",
                "Vehicle diagnostics (DTCs)",
                "Geofence alerts"
            ),
            batteryLife = null, // Vehicle powered
            commonLocations = listOf("OBD-II port under driver's side dashboard"),
            legalStatus = "Legal for vehicle owners; requires consent for others",
            threatScore = 75
        ),

        GpsTrackerProfile(
            id = "vyncs",
            name = "Vyncs GPS Tracker",
            category = GpsTrackerCategory.OBD_II_PORT,
            manufacturer = "Vyncs (Agnik)",
            powerSource = PowerSource.OBD_VEHICLE_POWER,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Consumer vehicle tracking",
                "Insurance telematics",
                "Parental monitoring"
            ),
            physicalDescription = "OBD-II dongle, black plastic, ~2x2 inches",
            detectionMethods = listOf(
                "Visual inspection of OBD-II port",
                "Device has LED indicators when active",
                "Check for unfamiliar device in vehicle"
            ),
            dataCollected = listOf(
                "GPS location",
                "Driving behavior analytics",
                "Vehicle health data",
                "Fuel economy",
                "Trip summaries"
            ),
            batteryLife = null,
            commonLocations = listOf("OBD-II port"),
            legalStatus = "Legal for vehicle owners",
            threatScore = 70
        ),

        GpsTrackerProfile(
            id = "motosafety",
            name = "MOTOsafety GPS Tracker",
            category = GpsTrackerCategory.OBD_II_PORT,
            manufacturer = "MOTOsafety",
            powerSource = PowerSource.OBD_VEHICLE_POWER,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Teen driver monitoring",
                "Business fleet tracking",
                "Family vehicle monitoring"
            ),
            physicalDescription = "OBD-II plug device, branded logo visible",
            detectionMethods = listOf(
                "Inspect OBD-II port",
                "Look for 'MOTOsafety' branding",
                "Check mobile app stores for active subscriptions"
            ),
            dataCollected = listOf(
                "Real-time GPS tracking",
                "Speed alerts",
                "Curfew violations",
                "Rapid acceleration/braking",
                "Idle time"
            ),
            batteryLife = null,
            commonLocations = listOf("OBD-II port"),
            legalStatus = "Legal for vehicle owners; consent required for non-owners",
            threatScore = 70
        ),

        // Magnetic GPS Trackers
        GpsTrackerProfile(
            id = "landairsea_overdrive",
            name = "LandAirSea Overdrive",
            category = GpsTrackerCategory.MAGNETIC,
            manufacturer = "LandAirSea",
            powerSource = PowerSource.INTERNAL_BATTERY,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Private investigation",
                "Law enforcement (with warrant)",
                "Asset tracking",
                "Stalking (illegal use)"
            ),
            physicalDescription = "Small black box, ~3x2x1 inches, strong magnet on bottom, waterproof",
            detectionMethods = listOf(
                "Physical search of vehicle undercarriage",
                "Check wheel wells (all four)",
                "Inspect behind bumpers",
                "Look in trunk spare tire area",
                "Use flashlight to check frame rails",
                "Feel for magnetic attachment points"
            ),
            dataCollected = listOf(
                "GPS location (configurable intervals)",
                "Movement history",
                "Geofence alerts",
                "Speed tracking",
                "Battery level"
            ),
            batteryLife = "Up to 2 weeks active / 6 months standby",
            commonLocations = listOf(
                "Under vehicle frame",
                "Inside wheel wells",
                "Behind bumpers (front/rear)",
                "Under trunk/cargo area",
                "Attached to metal frame components"
            ),
            legalStatus = "Requires warrant for law enforcement; illegal for unauthorized tracking",
            threatScore = 85
        ),

        GpsTrackerProfile(
            id = "spytec_gl300",
            name = "SpyTec GL300",
            category = GpsTrackerCategory.MAGNETIC,
            manufacturer = "SpyTec",
            powerSource = PowerSource.INTERNAL_BATTERY,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Personal asset tracking",
                "Vehicle tracking",
                "Private investigation",
                "Rental car monitoring"
            ),
            physicalDescription = "Small rectangular device, ~3x1.5x1 inches, waterproof case available",
            detectionMethods = listOf(
                "Thorough vehicle search",
                "Magnetic sweep of undercarriage",
                "Check all hidden compartments",
                "Inspect wheel wells"
            ),
            dataCollected = listOf(
                "Real-time GPS location",
                "Historical tracking data",
                "Geofence alerts",
                "Speed monitoring"
            ),
            batteryLife = "Up to 2.5 weeks",
            commonLocations = listOf(
                "Vehicle undercarriage",
                "Wheel wells",
                "Inside personal bags/belongings"
            ),
            legalStatus = "Legal for own property; illegal for tracking others without consent",
            threatScore = 80
        ),

        GpsTrackerProfile(
            id = "optimus_2",
            name = "Optimus 2.0 GPS Tracker",
            category = GpsTrackerCategory.MAGNETIC,
            manufacturer = "Optimus",
            powerSource = PowerSource.INTERNAL_BATTERY,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Vehicle tracking",
                "Teen monitoring",
                "Asset protection"
            ),
            physicalDescription = "Compact black device with magnetic case option",
            detectionMethods = listOf(
                "Physical vehicle inspection",
                "Magnetic wand sweep",
                "Check common hiding spots"
            ),
            dataCollected = listOf(
                "GPS coordinates",
                "Speed",
                "Trip history",
                "SOS alerts"
            ),
            batteryLife = "Up to 2 weeks",
            commonLocations = listOf(
                "Under vehicle",
                "In wheel wells",
                "Attached to frame"
            ),
            legalStatus = "Requires consent for tracking individuals",
            threatScore = 80
        ),

        // Hardwired Trackers
        GpsTrackerProfile(
            id = "fleet_hardwired",
            name = "Hardwired Fleet Tracker",
            category = GpsTrackerCategory.HARDWIRED,
            manufacturer = null,
            powerSource = PowerSource.VEHICLE_HARDWIRE,
            backhaul = BackhaulType.CELLULAR_4G,
            typicalDeployment = listOf(
                "Commercial fleet management",
                "Professional installation",
                "Long-term vehicle tracking",
                "Law enforcement (with warrant)"
            ),
            physicalDescription = "Small black box connected to vehicle wiring, often hidden in dashboard or under seats",
            detectionMethods = listOf(
                "Professional TSCM sweep",
                "Check for non-factory wiring",
                "Inspect under dashboard",
                "Look behind panels",
                "Trace unusual wires to hidden devices",
                "Use RF detector near wiring harness"
            ),
            dataCollected = listOf(
                "Continuous GPS tracking",
                "Vehicle ignition status",
                "Mileage",
                "Driver behavior",
                "Engine diagnostics"
            ),
            batteryLife = null, // Hardwired
            commonLocations = listOf(
                "Behind dashboard panels",
                "Under driver/passenger seats",
                "In center console",
                "Near OBD-II port (tapped into)",
                "Inside door panels"
            ),
            legalStatus = "Professional installation; requires consent or warrant",
            threatScore = 90
        )
    )

    // ==================== DRONE DETECTION PROFILES ====================

    /**
     * Comprehensive drone profiles with Remote ID information.
     * Remote ID became mandatory in the US starting September 2023.
     */
    data class DroneProfile(
        val id: String,
        val name: String,
        val manufacturer: String,
        val category: DroneCategory,
        val controlFrequencies: List<Long>,
        val controlProtocol: String,
        val hasRemoteId: Boolean, // Required since Sept 2023 in US
        val remoteIdBroadcast: RemoteIdBroadcastType?,
        val wifiPatterns: List<String>,
        val blePatterns: List<String>,
        val ouiPrefixes: List<String>,
        val typicalRange: String,
        val suspiciousIndicators: List<String>,
        val legalRequirements: List<String>
    )

    enum class DroneCategory(val displayName: String) {
        CONSUMER("Consumer/Prosumer"),
        COMMERCIAL("Commercial"),
        RACING_FPV("Racing/FPV"),
        ENTERPRISE("Enterprise"),
        GOVERNMENT("Government/Law Enforcement"),
        DIY("DIY/Custom Built")
    }

    enum class RemoteIdBroadcastType(val displayName: String) {
        WIFI_BEACON("WiFi Beacon Advertisement"),
        BLUETOOTH_5("Bluetooth 5 Long Range"),
        BOTH("WiFi + Bluetooth"),
        NONE("No Remote ID (illegal in US since 9/2023)")
    }

    val droneProfiles = listOf(
        DroneProfile(
            id = "dji_mavic",
            name = "DJI Mavic Series",
            manufacturer = "DJI",
            category = DroneCategory.CONSUMER,
            controlFrequencies = listOf(2_400_000_000L, 5_800_000_000L),
            controlProtocol = "OcuSync 2.0/3.0 (proprietary)",
            hasRemoteId = true,
            remoteIdBroadcast = RemoteIdBroadcastType.WIFI_BEACON,
            wifiPatterns = listOf("(?i)^mavic[-_]?(pro|air|mini|[0-9]).*"),
            blePatterns = listOf("(?i)^(mavic|dji)[-_]?.*"),
            ouiPrefixes = listOf("60:60:1F", "34:D2:62", "48:1C:B9", "60:C7:98"),
            typicalRange = "Up to 10km (OcuSync 3.0)",
            suspiciousIndicators = listOf(
                "Hovering over private property",
                "Following specific person/vehicle",
                "Operating at night without lights",
                "No Remote ID broadcast (illegal)",
                "Operating near airports/restricted areas"
            ),
            legalRequirements = listOf(
                "Remote ID broadcast required (US since 9/2023)",
                "FAA registration required for >250g",
                "Visual line of sight required (unless waiver)",
                "Cannot fly over people without certification"
            )
        ),

        DroneProfile(
            id = "dji_phantom",
            name = "DJI Phantom Series",
            manufacturer = "DJI",
            category = DroneCategory.CONSUMER,
            controlFrequencies = listOf(2_400_000_000L, 5_800_000_000L),
            controlProtocol = "Lightbridge/OcuSync",
            hasRemoteId = true,
            remoteIdBroadcast = RemoteIdBroadcastType.WIFI_BEACON,
            wifiPatterns = listOf("(?i)^phantom[-_]?[0-9].*"),
            blePatterns = listOf("(?i)^phantom.*"),
            ouiPrefixes = listOf("60:60:1F", "60:C7:98"),
            typicalRange = "Up to 7km",
            suspiciousIndicators = listOf(
                "Large drone near residence",
                "Extended hovering",
                "Camera pointed at windows"
            ),
            legalRequirements = listOf(
                "Remote ID required",
                "FAA registration required"
            )
        ),

        DroneProfile(
            id = "autel_evo",
            name = "Autel EVO Series",
            manufacturer = "Autel Robotics",
            category = DroneCategory.CONSUMER,
            controlFrequencies = listOf(2_400_000_000L, 5_800_000_000L),
            controlProtocol = "Autel SkyLink",
            hasRemoteId = true,
            remoteIdBroadcast = RemoteIdBroadcastType.WIFI_BEACON,
            wifiPatterns = listOf("(?i)^(autel|evo)[-_]?(ii|2|lite).*"),
            blePatterns = listOf("(?i)^autel.*"),
            ouiPrefixes = listOf(), // Add when known
            typicalRange = "Up to 9km",
            suspiciousIndicators = listOf(
                "US-made drone alternative to DJI",
                "May be used by government/enterprises"
            ),
            legalRequirements = listOf(
                "Remote ID required",
                "FAA registration required"
            )
        ),

        DroneProfile(
            id = "skydio",
            name = "Skydio Drones",
            manufacturer = "Skydio (USA)",
            category = DroneCategory.ENTERPRISE,
            controlFrequencies = listOf(2_400_000_000L, 5_800_000_000L),
            controlProtocol = "Skydio Autonomy",
            hasRemoteId = true,
            remoteIdBroadcast = RemoteIdBroadcastType.BOTH,
            wifiPatterns = listOf("(?i)^skydio[-_]?[0-9x].*"),
            blePatterns = listOf("(?i)^skydio.*"),
            ouiPrefixes = listOf(),
            typicalRange = "Up to 6km",
            suspiciousIndicators = listOf(
                "US-made autonomous drone",
                "Used by law enforcement/military",
                "Autonomous following capability"
            ),
            legalRequirements = listOf(
                "Remote ID required",
                "Often used by government agencies"
            )
        ),

        DroneProfile(
            id = "parrot_anafi",
            name = "Parrot Anafi",
            manufacturer = "Parrot (France)",
            category = DroneCategory.CONSUMER,
            controlFrequencies = listOf(2_400_000_000L, 5_800_000_000L),
            controlProtocol = "WiFi Direct",
            hasRemoteId = true,
            remoteIdBroadcast = RemoteIdBroadcastType.WIFI_BEACON,
            wifiPatterns = listOf("(?i)^(parrot|anafi|bebop)[-_]?.*"),
            blePatterns = listOf("(?i)^parrot.*"),
            ouiPrefixes = listOf("A0:14:3D", "90:03:B7", "00:12:1C", "00:26:7E"),
            typicalRange = "Up to 4km",
            suspiciousIndicators = listOf(
                "French-made drone",
                "Thermal imaging variants exist"
            ),
            legalRequirements = listOf(
                "Remote ID required in US",
                "CE marking for EU operation"
            )
        )
    )

    /**
     * Remote ID information - broadcasts from drones since Sept 2023.
     * Per FAA regulations, drones must broadcast this information.
     */
    data class RemoteIdInfo(
        val serialNumber: String?,      // Drone serial number
        val latitude: Double?,          // Drone current location
        val longitude: Double?,
        val altitude: Double?,          // Altitude in meters
        val speed: Double?,             // Ground speed
        val heading: Double?,           // Direction of travel
        val operatorLatitude: Double?,  // Operator/controller location
        val operatorLongitude: Double?,
        val timestamp: Long,
        val emergencyStatus: Boolean
    )

    /**
     * Check Remote ID apps for drone detection:
     * - DroneScout (available on iOS/Android)
     * - OpenDroneID (open source reference)
     * - AirMap
     */
    val remoteIdDetectionApps = listOf(
        "DroneScout" to "Official FAA-recommended app",
        "OpenDroneID" to "Open source reference implementation",
        "AirMap" to "Commercial drone airspace management"
    )

    // ==================== JAMMING DEVICE SIGNATURES ====================

    /**
     * RF jamming device signatures and detection patterns.
     * NOTE: Jamming devices are ILLEGAL to operate in the US (FCC violation).
     */
    data class JammerProfile(
        val id: String,
        val name: String,
        val targetedBands: List<JammedBand>,
        val detectionSigns: List<String>,
        val typicalUsers: List<String>,
        val legalStatus: String,
        val countermeasures: List<String>
    )

    data class JammedBand(
        val name: String,
        val frequencyRange: String,
        val affectedServices: List<String>
    )

    val jammerProfiles = listOf(
        JammerProfile(
            id = "gps_jammer",
            name = "GPS/GNSS Jammer",
            targetedBands = listOf(
                JammedBand("GPS L1", "1575.42 MHz", listOf("GPS navigation", "Fleet tracking", "Timing systems")),
                JammedBand("GPS L2", "1227.60 MHz", listOf("Precision GPS", "Survey equipment")),
                JammedBand("GLONASS L1", "1602 MHz", listOf("Russian navigation"))
            ),
            detectionSigns = listOf(
                "Sudden GPS signal loss while stationary",
                "GPS 'jumps' or erratic position",
                "GNSS receiver reports no satellites",
                "Multiple devices lose GPS simultaneously",
                "GPS accuracy degrades dramatically"
            ),
            typicalUsers = listOf(
                "Truckers avoiding fleet tracking (illegal)",
                "Criminals avoiding location tracking",
                "Car thieves defeating GPS trackers"
            ),
            legalStatus = "ILLEGAL in US (FCC violation) - up to \$100K fine + criminal charges",
            countermeasures = listOf(
                "Note time and location of jamming",
                "Report to FCC if persistent",
                "Use cellular-based tracking as backup",
                "Professional TSCM equipment can locate jammer"
            )
        ),

        JammerProfile(
            id = "cell_jammer",
            name = "Cellular Phone Jammer",
            targetedBands = listOf(
                JammedBand("2G/GSM", "850/900/1800/1900 MHz", listOf("2G voice calls", "SMS")),
                JammedBand("3G/UMTS", "850/1900/2100 MHz", listOf("3G calls/data")),
                JammedBand("4G/LTE", "700-2600 MHz", listOf("LTE data/calls", "Most smartphones"))
            ),
            detectionSigns = listOf(
                "All phones lose signal simultaneously",
                "Phones show 'No Service' or 'Emergency Only'",
                "Calls drop immediately when entering area",
                "Data connections fail completely",
                "Multiple carriers affected simultaneously"
            ),
            typicalUsers = listOf(
                "Prisons (with legal waiver)",
                "Theaters (ILLEGALLY)",
                "Criminals during robberies",
                "Exam cheating prevention (illegal)"
            ),
            legalStatus = "ILLEGAL in US except for federal government with waiver",
            countermeasures = listOf(
                "Leave the area if possible",
                "Use WiFi calling if WiFi is available",
                "Note location for reporting to FCC",
                "Cannot make 911 calls in jammed area - DANGER"
            )
        ),

        JammerProfile(
            id = "wifi_jammer",
            name = "WiFi Jammer",
            targetedBands = listOf(
                JammedBand("2.4 GHz", "2400-2483 MHz", listOf("WiFi", "Bluetooth", "IoT devices")),
                JammedBand("5 GHz", "5150-5850 MHz", listOf("WiFi 5/6", "FPV drones"))
            ),
            detectionSigns = listOf(
                "Massive deauth packets on all channels",
                "All WiFi networks become unreachable",
                "Bluetooth devices disconnect",
                "Smart home devices go offline",
                "Persistent interference across all channels"
            ),
            typicalUsers = listOf(
                "Criminals defeating security cameras",
                "Burglars disabling WiFi alarms",
                "Corporate espionage"
            ),
            legalStatus = "ILLEGAL - FCC violation",
            countermeasures = listOf(
                "Use wired security cameras as backup",
                "Local recording (SD card) for cameras",
                "Cellular backup for alarm systems",
                "Report persistent jamming to FCC"
            )
        )
    )

    // ==================== PROFESSIONAL SURVEILLANCE EQUIPMENT ====================

    /**
     * Professional-grade surveillance equipment profiles.
     * Used by law enforcement, private investigators, and TSCM professionals.
     */
    data class ProfessionalSurveillanceProfile(
        val id: String,
        val name: String,
        val category: ProfessionalCategory,
        val manufacturer: String?,
        val typicalUsers: List<String>,
        val detectionMethods: List<String>,
        val dataCollected: List<String>,
        val legalFramework: String,
        val rfCharacteristics: RfCharacteristics?
    )

    enum class ProfessionalCategory(val displayName: String) {
        LAW_ENFORCEMENT_TRACKER("Law Enforcement GPS Tracker"),
        AUDIO_BUG("Audio Transmitter/Bug"),
        GSM_BUG("GSM/Cellular Bug"),
        TSCM_EQUIPMENT("TSCM Sweep Equipment"),
        FORENSIC_TOOL("Digital Forensics Tool")
    }

    data class RfCharacteristics(
        val frequencyBands: List<String>,
        val modulationType: String?,
        val transmitPower: String?,
        val detectionDifficulty: String
    )

    val professionalSurveillanceProfiles = listOf(
        ProfessionalSurveillanceProfile(
            id = "le_vehicle_tracker",
            name = "Law Enforcement Vehicle Tracker",
            category = ProfessionalCategory.LAW_ENFORCEMENT_TRACKER,
            manufacturer = null, // Various
            typicalUsers = listOf(
                "FBI",
                "DEA",
                "State/Local Law Enforcement",
                "Federal agencies"
            ),
            detectionMethods = listOf(
                "Professional TSCM vehicle sweep",
                "RF detector sweep of vehicle",
                "Physical inspection by trained professional",
                "Check for unusual cellular activity from vehicle",
                "Look for magnetic devices under vehicle"
            ),
            dataCollected = listOf(
                "Real-time GPS location",
                "Historical movement patterns",
                "Speed and direction",
                "Stop locations and duration",
                "Pattern of life analysis"
            ),
            legalFramework = "Requires court-approved warrant (US v. Jones 2012)",
            rfCharacteristics = RfCharacteristics(
                frequencyBands = listOf("4G LTE cellular bands"),
                modulationType = "Cellular protocol",
                transmitPower = "Low (battery conservation)",
                detectionDifficulty = "High - professional sweep required"
            )
        ),

        ProfessionalSurveillanceProfile(
            id = "uhf_audio_bug",
            name = "UHF Audio Transmitter (Bug)",
            category = ProfessionalCategory.AUDIO_BUG,
            manufacturer = null,
            typicalUsers = listOf(
                "Law enforcement (with warrant)",
                "Corporate espionage (illegal)",
                "Private investigators",
                "Stalkers (illegal)"
            ),
            detectionMethods = listOf(
                "Sweep with RF detector in 300-500 MHz range",
                "Non-linear junction detector (NLJD)",
                "Physical search for hidden devices",
                "Check power outlets, lamps, smoke detectors",
                "Thermal imaging for active electronics"
            ),
            dataCollected = listOf(
                "Audio conversations",
                "Ambient sounds",
                "Voice recordings"
            ),
            legalFramework = "Wiretapping laws vary by state; generally requires warrant or consent",
            rfCharacteristics = RfCharacteristics(
                frequencyBands = listOf("300-500 MHz (UHF)"),
                modulationType = "FM/AM narrowband",
                transmitPower = "10-100 mW typically",
                detectionDifficulty = "Medium - RF sweep can detect active transmission"
            )
        ),

        ProfessionalSurveillanceProfile(
            id = "vhf_audio_bug",
            name = "VHF Audio Transmitter",
            category = ProfessionalCategory.AUDIO_BUG,
            manufacturer = null,
            typicalUsers = listOf(
                "Older surveillance equipment",
                "Budget devices"
            ),
            detectionMethods = listOf(
                "RF detector sweep in 100-300 MHz range",
                "Broadband receiver scan",
                "Physical inspection"
            ),
            dataCollected = listOf(
                "Audio conversations"
            ),
            legalFramework = "Wiretapping laws apply",
            rfCharacteristics = RfCharacteristics(
                frequencyBands = listOf("100-300 MHz (VHF)"),
                modulationType = "FM typically",
                transmitPower = "Variable",
                detectionDifficulty = "Medium-Low - older technology, easier to detect"
            )
        ),

        ProfessionalSurveillanceProfile(
            id = "gsm_bug",
            name = "GSM/Cellular Audio Bug",
            category = ProfessionalCategory.GSM_BUG,
            manufacturer = null,
            typicalUsers = listOf(
                "Surveillance professionals",
                "Corporate espionage",
                "Private investigators"
            ),
            detectionMethods = listOf(
                "Call the room and listen for ringtone",
                "RF detector checking for cellular activity",
                "Cell network analyzer looking for unknown devices",
                "Physical search of room",
                "Check for SIM cards in unusual objects"
            ),
            dataCollected = listOf(
                "Audio - caller can listen remotely",
                "Some models have GPS",
                "Can be activated remotely via phone call"
            ),
            legalFramework = "Wiretapping laws apply; illegal without consent/warrant",
            rfCharacteristics = RfCharacteristics(
                frequencyBands = listOf("Cellular bands (850/900/1800/1900 MHz)"),
                modulationType = "GSM/LTE cellular",
                transmitPower = "Standard cellular",
                detectionDifficulty = "High - only transmits when activated"
            )
        ),

        ProfessionalSurveillanceProfile(
            id = "tscm_equipment",
            name = "TSCM Sweep Equipment",
            category = ProfessionalCategory.TSCM_EQUIPMENT,
            manufacturer = "REI, JJN Digital, Others",
            typicalUsers = listOf(
                "TSCM professionals",
                "Corporate security",
                "Government counter-intelligence"
            ),
            detectionMethods = listOf(
                "If detected, may indicate sweep in progress",
                "Look for professional vehicles/personnel",
                "High-end RF detection equipment signatures"
            ),
            dataCollected = listOf(
                "Detects bugs, not collects data",
                "Spectrum analysis",
                "NLJD signatures"
            ),
            legalFramework = "Legal for authorized security sweeps",
            rfCharacteristics = RfCharacteristics(
                frequencyBands = listOf("Wideband receivers", "0-6 GHz coverage typically"),
                modulationType = "Receiver only",
                transmitPower = "N/A - detection equipment",
                detectionDifficulty = "N/A"
            )
        )
    )

    // ==================== SMART HOME / IoT SECURITY PATTERNS ====================

    /**
     * Extended smart home and IoT security profiles with privacy context.
     */
    data class SmartHomeSecurityProfile(
        val id: String,
        val name: String,
        val manufacturer: String,
        val deviceType: SmartHomeDeviceType,
        val wifiCharacteristics: WifiCharacteristics,
        val bleCharacteristics: BleCharacteristics?,
        val cloudDependency: CloudDependency,
        val lawEnforcementAccess: LawEnforcementAccessProfile,
        val privacyConcerns: List<String>,
        val networkPatterns: List<String>
    )

    enum class SmartHomeDeviceType(val displayName: String) {
        VIDEO_DOORBELL("Video Doorbell"),
        SECURITY_CAMERA("Security Camera"),
        MESH_NETWORK("Mesh Network/Sidewalk"),
        SMART_SPEAKER("Smart Speaker"),
        SMART_LOCK("Smart Lock"),
        BABY_MONITOR("Baby Monitor"),
        MATTER_THREAD("Matter/Thread Device")
    }

    data class WifiCharacteristics(
        val ssidPatterns: List<String>,
        val macPrefixes: List<String>,
        val defaultPorts: List<Int>
    )

    data class BleCharacteristics(
        val namePatterns: List<String>,
        val serviceUuids: List<String>
    )

    enum class CloudDependency(val displayName: String, val description: String) {
        MANDATORY("Cloud Required", "Device requires cloud connection to function"),
        OPTIONAL("Cloud Optional", "Local control possible but cloud available"),
        LOCAL_ONLY("Local Only", "No cloud connectivity - fully local"),
        HYBRID("Hybrid", "Some features require cloud, basic function local")
    }

    data class LawEnforcementAccessProfile(
        val hasPartnership: Boolean,
        val partnershipDetails: String?,
        val canRequestWithoutWarrant: Boolean,
        val ownerNotified: Boolean
    )

    val smartHomeSecurityProfiles = listOf(
        SmartHomeSecurityProfile(
            id = "ring_doorbell",
            name = "Ring Video Doorbell",
            manufacturer = "Ring (Amazon)",
            deviceType = SmartHomeDeviceType.VIDEO_DOORBELL,
            wifiCharacteristics = WifiCharacteristics(
                ssidPatterns = listOf("(?i)^ring[_-]?(doorbell|cam|setup|stick).*"),
                macPrefixes = listOf("44:73:D6", "18:B4:30", "0C:47:C9"),
                defaultPorts = listOf(443, 9999)
            ),
            bleCharacteristics = BleCharacteristics(
                namePatterns = listOf("(?i)^ring[_-]?.*"),
                serviceUuids = listOf()
            ),
            cloudDependency = CloudDependency.MANDATORY,
            lawEnforcementAccess = LawEnforcementAccessProfile(
                hasPartnership = true,
                partnershipDetails = "Partners with 2,500+ police departments via Neighbors app. " +
                    "Police can request footage through Neighbors Public Safety Service.",
                canRequestWithoutWarrant = true,
                ownerNotified = false // Under new policy (2022), Ring requires warrant unless emergency
            ),
            privacyConcerns = listOf(
                "Footage shared with 2,500+ law enforcement agencies",
                "Neighbors app creates surveillance network",
                "Audio recording range: 15-25 feet",
                "Video recording: 180 degree view",
                "Cloud storage on Amazon servers",
                "Facial recognition capability (Neighbors app)",
                "Always-on microphone"
            ),
            networkPatterns = listOf(
                "Constant outbound connection to Ring servers",
                "Video upload on motion detection",
                "Periodic health check packets"
            )
        ),

        SmartHomeSecurityProfile(
            id = "amazon_sidewalk",
            name = "Amazon Sidewalk Network",
            manufacturer = "Amazon",
            deviceType = SmartHomeDeviceType.MESH_NETWORK,
            wifiCharacteristics = WifiCharacteristics(
                ssidPatterns = listOf("(?i)^(amazon[_-]?sidewalk|sidewalk[_-]?bridge).*"),
                macPrefixes = listOf("44:73:D6", "18:B4:30"),
                defaultPorts = listOf()
            ),
            bleCharacteristics = BleCharacteristics(
                namePatterns = listOf("(?i)^sidewalk.*"),
                serviceUuids = listOf()
            ),
            cloudDependency = CloudDependency.MANDATORY,
            lawEnforcementAccess = LawEnforcementAccessProfile(
                hasPartnership = true,
                partnershipDetails = "Part of Amazon ecosystem with Ring partnerships",
                canRequestWithoutWarrant = false,
                ownerNotified = true
            ),
            privacyConcerns = listOf(
                "Uses 900 MHz (LoRa) and Bluetooth for mesh network",
                "Borrows bandwidth from Ring/Echo devices",
                "Can track Tile trackers via network",
                "Privacy: Shared with neighbors' devices",
                "Creates neighborhood-wide tracking network",
                "Low-bandwidth but persistent connectivity"
            ),
            networkPatterns = listOf(
                "900 MHz LoRa transmissions",
                "BLE advertisements",
                "Mesh routing between Amazon devices"
            )
        ),

        SmartHomeSecurityProfile(
            id = "matter_thread",
            name = "Matter/Thread Device",
            manufacturer = "Various (Standard)",
            deviceType = SmartHomeDeviceType.MATTER_THREAD,
            wifiCharacteristics = WifiCharacteristics(
                ssidPatterns = listOf(),
                macPrefixes = listOf(),
                defaultPorts = listOf(5540) // Matter port
            ),
            bleCharacteristics = BleCharacteristics(
                namePatterns = listOf(),
                serviceUuids = listOf("FFF6") // Matter commissioning
            ),
            cloudDependency = CloudDependency.OPTIONAL,
            lawEnforcementAccess = LawEnforcementAccessProfile(
                hasPartnership = false,
                partnershipDetails = "Local control standard - varies by manufacturer",
                canRequestWithoutWarrant = false,
                ownerNotified = true
            ),
            privacyConcerns = listOf(
                "New smart home standard (2022+)",
                "Uses 2.4 GHz (Thread) or WiFi",
                "Local control possible - more private than cloud-dependent",
                "Interoperability across ecosystems",
                "Privacy depends on manufacturer implementation"
            ),
            networkPatterns = listOf(
                "Thread mesh on 2.4 GHz (IEEE 802.15.4)",
                "Matter over WiFi",
                "Local mDNS/DNS-SD discovery"
            )
        )
    )

    // ==================== LAW ENFORCEMENT EQUIPMENT PROFILES ====================

    /**
     * Detailed law enforcement equipment profiles for detection.
     */
    data class LawEnforcementEquipmentProfile(
        val id: String,
        val name: String,
        val category: LEEquipmentCategory,
        val manufacturer: String,
        val wifiPatterns: List<String>,
        val blePatterns: List<String>,
        val macPrefixes: List<String>,
        val capabilities: List<String>,
        val dataUploaded: String,
        val detectionIndicators: List<String>
    )

    enum class LEEquipmentCategory(val displayName: String) {
        BODY_CAMERA("Body Worn Camera"),
        IN_CAR_VIDEO("In-Car Video System"),
        LPR_ALPR("License Plate Reader"),
        RADIO_SYSTEM("Radio Communication"),
        FORENSIC_TOOL("Forensic Tool")
    }

    val lawEnforcementEquipmentProfiles = listOf(
        // Body Worn Cameras
        LawEnforcementEquipmentProfile(
            id = "axon_body_3",
            name = "Axon Body 3/4",
            category = LEEquipmentCategory.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            wifiPatterns = listOf("(?i)^axon[_-]?(body|signal).*", "(?i)^ab[234].*"),
            blePatterns = listOf("(?i)^axon.*", "(?i)^(body|flex)[_-]?[234].*"),
            macPrefixes = listOf(), // Nordic Semiconductor typically
            capabilities = listOf(
                "HD video recording",
                "GPS location logging",
                "Automatic activation via Axon Signal",
                "Real-time streaming (Axon Respond)",
                "WiFi and LTE upload",
                "10+ hour battery life"
            ),
            dataUploaded = "Evidence.com cloud storage",
            detectionIndicators = listOf(
                "BLE beacon for Axon Signal triggers",
                "WiFi connection for docking/upload",
                "Axon-specific BLE service UUIDs"
            )
        ),

        LawEnforcementEquipmentProfile(
            id = "motorola_si500",
            name = "Motorola Si500",
            category = LEEquipmentCategory.BODY_CAMERA,
            manufacturer = "Motorola Solutions",
            wifiPatterns = listOf("(?i)^(moto|si)[_-]?500.*", "(?i)^v[35]00.*"),
            blePatterns = listOf("(?i)^(si|v)[0-9]+.*"),
            macPrefixes = listOf(),
            capabilities = listOf(
                "Video recording",
                "WiFi upload",
                "BLE connectivity",
                "Integration with Motorola radios"
            ),
            dataUploaded = "CommandCentral Vault",
            detectionIndicators = listOf(
                "Motorola BLE advertising",
                "WiFi upload patterns"
            )
        ),

        LawEnforcementEquipmentProfile(
            id = "digital_ally_firstvu",
            name = "Digital Ally FirstVU",
            category = LEEquipmentCategory.BODY_CAMERA,
            manufacturer = "Digital Ally",
            wifiPatterns = listOf("(?i)^(digital[_-]?ally|firstvu).*"),
            blePatterns = listOf("(?i)^(da|firstvu).*"),
            macPrefixes = listOf(),
            capabilities = listOf(
                "HD video",
                "WiFi upload",
                "Cloud storage"
            ),
            dataUploaded = "Digital Ally cloud",
            detectionIndicators = listOf(
                "WiFi direct for upload",
                "Digital Ally branding in SSID"
            )
        ),

        LawEnforcementEquipmentProfile(
            id = "watchguard_4re",
            name = "WatchGuard 4RE",
            category = LEEquipmentCategory.IN_CAR_VIDEO,
            manufacturer = "Motorola Solutions (WatchGuard)",
            wifiPatterns = listOf("(?i)^watchguard.*", "(?i)^4re.*"),
            blePatterns = listOf("(?i)^watchguard.*"),
            macPrefixes = listOf(),
            capabilities = listOf(
                "Multiple camera angles",
                "In-car video recording",
                "Body camera integration",
                "WiFi/cellular upload",
                "Automatic trigger on lights/siren"
            ),
            dataUploaded = "Evidence Library cloud",
            detectionIndicators = listOf(
                "WiFi AP in patrol vehicle",
                "Multiple camera streams",
                "Integration with body cameras"
            )
        ),

        // License Plate Readers
        LawEnforcementEquipmentProfile(
            id = "vigilant_motorola",
            name = "Vigilant ALPR (Motorola)",
            category = LEEquipmentCategory.LPR_ALPR,
            manufacturer = "Motorola Solutions",
            wifiPatterns = listOf("(?i)^vigilant.*"),
            blePatterns = listOf(),
            macPrefixes = listOf(),
            capabilities = listOf(
                "Mobile and fixed ALPR",
                "Real-time plate lookups",
                "Integration with LEARN database",
                "Hot list alerts"
            ),
            dataUploaded = "Vigilant LEARN database",
            detectionIndicators = listOf(
                "WiFi upload from mobile unit",
                "Cellular connectivity",
                "IR illumination visible at night"
            )
        ),

        LawEnforcementEquipmentProfile(
            id = "flock_safety_lpr",
            name = "Flock Safety ALPR",
            category = LEEquipmentCategory.LPR_ALPR,
            manufacturer = "Flock Safety",
            wifiPatterns = listOf("(?i)^flock.*", "(?i)^falcon.*", "(?i)^sparrow.*", "(?i)^condor.*"),
            blePatterns = listOf("(?i)^flock.*"),
            macPrefixes = listOf("50:29:4D", "86:25:19"), // Quectel modems
            capabilities = listOf(
                "License plate capture",
                "Vehicle fingerprint (make/model/color)",
                "Direction of travel",
                "Real-time alerts",
                "Cross-jurisdiction sharing"
            ),
            dataUploaded = "Flock Safety cloud (30-day retention typically)",
            detectionIndicators = listOf(
                "Solar-powered pole mount",
                "Quectel LTE modem OUI",
                "Flock SSID patterns"
            )
        ),

        LawEnforcementEquipmentProfile(
            id = "elsag_alpr",
            name = "ELSAG ALPR",
            category = LEEquipmentCategory.LPR_ALPR,
            manufacturer = "Leonardo DRS",
            wifiPatterns = listOf("(?i)^elsag.*"),
            blePatterns = listOf(),
            macPrefixes = listOf(),
            capabilities = listOf(
                "Mobile ALPR on patrol vehicles",
                "Fixed position cameras",
                "EOC integration"
            ),
            dataUploaded = "ELSAG Enterprise Operations Center",
            detectionIndicators = listOf(
                "Mounted on patrol vehicle",
                "Multiple cameras per vehicle",
                "IR flash at night"
            )
        )
    )

    // ==================== CONFIRMATION METHODS ====================

    /**
     * Confirmation methods for suspected surveillance devices.
     * Practical steps users can take to verify detections.
     */
    data class ConfirmationMethod(
        val deviceCategory: String,
        val steps: List<ConfirmationStep>,
        val equipment: List<String>,
        val warnings: List<String>
    )

    data class ConfirmationStep(
        val order: Int,
        val action: String,
        val details: String,
        val difficulty: ConfirmationDifficulty
    )

    enum class ConfirmationDifficulty(val displayName: String) {
        EASY("Easy - No special equipment"),
        MODERATE("Moderate - Basic tools/apps"),
        DIFFICULT("Difficult - Special equipment"),
        PROFESSIONAL("Professional - TSCM expertise required")
    }

    val confirmationMethods = mapOf(
        "hidden_camera" to ConfirmationMethod(
            deviceCategory = "Hidden Cameras",
            steps = listOf(
                ConfirmationStep(1, "Visual inspection with flashlight",
                    "Shine a bright flashlight around the room looking for reflections from camera lenses. " +
                    "Camera lenses will reflect light differently than other surfaces.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(2, "Scan with phone camera for IR LEDs",
                    "Use your phone's front camera (less IR filtering) to look for invisible IR LEDs. " +
                    "Night vision cameras emit IR light that phone cameras can detect as purple/white glow.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(3, "Check for small holes in objects",
                    "Examine smoke detectors, clocks, USB chargers, air fresheners, picture frames, " +
                    "and other common objects for tiny holes that could house a camera.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(4, "Use RF detector in sweep mode",
                    "If the camera transmits wirelessly, an RF detector can locate it. " +
                    "Sweep the room slowly, focusing on areas where detection app showed signals.",
                    ConfirmationDifficulty.MODERATE),
                ConfirmationStep(5, "Network scan for unknown devices",
                    "Use a network scanner app to identify all devices on the WiFi network. " +
                    "Unknown devices with video-related ports may be cameras.",
                    ConfirmationDifficulty.MODERATE)
            ),
            equipment = listOf(
                "Flashlight (bright)",
                "Smartphone camera (front camera preferred)",
                "RF detector (optional)",
                "WiFi network scanner app"
            ),
            warnings = listOf(
                "Do not tamper with devices in rental properties - document and report instead",
                "Hidden cameras in private spaces are illegal in most jurisdictions",
                "If you find a camera, take photos before touching it"
            )
        ),

        "gps_tracker" to ConfirmationMethod(
            deviceCategory = "GPS Trackers",
            steps = listOf(
                ConfirmationStep(1, "Inspect OBD-II port",
                    "Check the OBD-II diagnostic port under the driver's side dashboard. " +
                    "Look for any plugged-in device that isn't a standard code reader.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(2, "Check vehicle undercarriage",
                    "Use a flashlight to inspect under the vehicle, focusing on flat metal surfaces " +
                    "where a magnetic tracker could attach. Check frame rails and crossmembers.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(3, "Inspect wheel wells",
                    "Feel inside all four wheel wells for magnetic devices. " +
                    "Trackers are often placed in the plastic liner or on metal components.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(4, "Check bumpers and trunk",
                    "Look behind front and rear bumpers (accessible from below). " +
                    "Check spare tire compartment and trunk lining.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(5, "Professional TSCM sweep",
                    "For hardwired trackers or persistent suspicion, hire a professional " +
                    "TSCM (Technical Surveillance Countermeasures) expert.",
                    ConfirmationDifficulty.PROFESSIONAL)
            ),
            equipment = listOf(
                "Flashlight",
                "Mirror on stick (for hard to see areas)",
                "Mechanic's creeper (for under-vehicle inspection)",
                "Magnetic wand or stud finder (helps locate magnetic devices)"
            ),
            warnings = listOf(
                "If you find a tracker, DO NOT remove it immediately - it may be law enforcement",
                "Document the device with photos before any action",
                "Removing a tracker may be destruction of property if it belongs to someone else",
                "Consult an attorney if you suspect illegal tracking"
            )
        ),

        "drone" to ConfirmationMethod(
            deviceCategory = "Drones",
            steps = listOf(
                ConfirmationStep(1, "Visual scan of sky",
                    "Look up and scan the sky for the drone. Most consumer drones are visible " +
                    "and audible within 100 meters. Look for flashing lights at night.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(2, "Listen for motor sound",
                    "Drones make a distinctive buzzing/whirring sound from their propellers. " +
                    "The sound is most noticeable when the drone is stationary/hovering.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(3, "Check Remote ID apps",
                    "Use DroneScout, OpenDroneID, or AirMap apps to detect Remote ID broadcasts. " +
                    "Since Sept 2023, most drones must broadcast their ID and location.",
                    ConfirmationDifficulty.MODERATE),
                ConfirmationStep(4, "WiFi scan for drone networks",
                    "Scan for WiFi networks with drone manufacturer names (DJI, Parrot, etc.). " +
                    "Drone control WiFi networks are usually visible when nearby.",
                    ConfirmationDifficulty.MODERATE),
                ConfirmationStep(5, "Track signal direction",
                    "Use the app's signal strength indicator to determine the drone's direction. " +
                    "Walk in the direction of stronger signal to locate.",
                    ConfirmationDifficulty.MODERATE)
            ),
            equipment = listOf(
                "Remote ID app (DroneScout, OpenDroneID)",
                "Binoculars (for distant drones)",
                "WiFi scanner app"
            ),
            warnings = listOf(
                "Do not attempt to shoot down or interfere with drones - this is illegal",
                "Drones without Remote ID are illegal in US since Sept 2023",
                "If drone is over your property, document and report to local authorities",
                "Commercial/government drones may be operating legally with permissions"
            )
        ),

        "audio_bug" to ConfirmationMethod(
            deviceCategory = "Audio Bugs/Transmitters",
            steps = listOf(
                ConfirmationStep(1, "Visual inspection of room",
                    "Check electrical outlets, power strips, smoke detectors, lamps, and USB chargers. " +
                    "Bugs are often hidden in or near power sources.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(2, "Listen for feedback",
                    "Call your own phone and listen for electronic feedback or clicking. " +
                    "Some bugs cause interference with phone signals.",
                    ConfirmationDifficulty.EASY),
                ConfirmationStep(3, "RF detector sweep",
                    "Use an RF detector to sweep the room. Focus on the frequency ranges: " +
                    "100-300 MHz (VHF) and 300-500 MHz (UHF) for traditional bugs.",
                    ConfirmationDifficulty.MODERATE),
                ConfirmationStep(4, "GSM bug detection",
                    "Use a cellular activity detector or make calls to find GSM bugs. " +
                    "These only transmit when active, making them harder to detect.",
                    ConfirmationDifficulty.DIFFICULT),
                ConfirmationStep(5, "Professional TSCM sweep",
                    "For high-assurance situations, hire a TSCM professional with " +
                    "spectrum analyzers and non-linear junction detectors.",
                    ConfirmationDifficulty.PROFESSIONAL)
            ),
            equipment = listOf(
                "Flashlight",
                "RF detector (pocket-sized available ~\$20-100)",
                "Cell phone for interference testing",
                "NLJD (professional equipment)"
            ),
            warnings = listOf(
                "Do not discuss sensitive topics while searching - assume you're being heard",
                "If found, document before removal",
                "Wiretapping is illegal - report to law enforcement if you find a bug",
                "In some states, all-party consent is required for recording"
            )
        )
    )

    // ==================== DEVICE METADATA EXTENSIONS ====================

    /**
     * Extended metadata for device profiles including operational context.
     */
    data class DeviceOperationalMetadata(
        val deviceType: DeviceType,
        val estimatedRange: String,
        val powerRequirements: String,
        val typicalDeploymentScenarios: List<String>,
        val legalStatus: LegalStatus,
        val dataCollected: List<String>,
        val dataRetention: String,
        val typicalOperators: List<String>,
        val counterDetectionDifficulty: String,
        val recommendedResponse: List<String>
    )

    data class LegalStatus(
        val generalStatus: String,
        val variances: String?,
        val relevantLaws: List<String>
    )

    val deviceOperationalMetadata = mapOf(
        DeviceType.HIDDEN_CAMERA to DeviceOperationalMetadata(
            deviceType = DeviceType.HIDDEN_CAMERA,
            estimatedRange = "WiFi cameras: network dependent; Analog: 50-500m depending on power",
            powerRequirements = "Battery (hours to days) or hardwired (continuous)",
            typicalDeploymentScenarios = listOf(
                "Airbnb/hotel room surveillance (illegal)",
                "Workplace monitoring (varies by jurisdiction)",
                "Nanny cams (legal with notice in most states)",
                "Voyeurism (illegal everywhere)"
            ),
            legalStatus = LegalStatus(
                generalStatus = "Illegal in private spaces without consent",
                variances = "Workplace rules vary; bathrooms/changing rooms always illegal",
                relevantLaws = listOf(
                    "Video Voyeurism Prevention Act (federal)",
                    "State wiretapping/eavesdropping laws",
                    "Invasion of privacy torts"
                )
            ),
            dataCollected = listOf(
                "Video footage (continuous or motion-triggered)",
                "Audio (if microphone equipped)",
                "Timestamps",
                "Motion events"
            ),
            dataRetention = "Varies - SD card storage or cloud (days to indefinite)",
            typicalOperators = listOf(
                "Airbnb/hotel guests checking for cameras",
                "Property owners (legal on own property)",
                "Stalkers/voyeurs (illegal)",
                "Private investigators"
            ),
            counterDetectionDifficulty = "Easy-Moderate: RF detection, lens reflection, IR detection",
            recommendedResponse = listOf(
                "Document the device with photos",
                "Do not touch or tamper with the device",
                "Report to property management/police",
                "Leave the area if in rental property"
            )
        ),

        DeviceType.DRONE to DeviceOperationalMetadata(
            deviceType = DeviceType.DRONE,
            estimatedRange = "Control: 1-10km depending on model; Visual: typically <500m",
            powerRequirements = "Battery - 15-45 minutes typical flight time",
            typicalDeploymentScenarios = listOf(
                "Recreational flying (legal with FAA rules)",
                "Photography/videography",
                "Real estate photography",
                "Law enforcement surveillance",
                "Stalking/harassment (illegal)",
                "Package delivery (Amazon, etc.)"
            ),
            legalStatus = LegalStatus(
                generalStatus = "Legal with FAA registration and Remote ID (since 9/2023)",
                variances = "Restricted near airports, over crowds, at night without waiver",
                relevantLaws = listOf(
                    "FAA Part 107 (commercial)",
                    "FAA recreational rules",
                    "State/local drone laws",
                    "Remote ID rule (effective 9/16/2023)"
                )
            ),
            dataCollected = listOf(
                "Video/photos",
                "GPS coordinates of footage",
                "Flight path/telemetry",
                "Potentially facial recognition"
            ),
            dataRetention = "Varies by operator",
            typicalOperators = listOf(
                "Recreational hobbyists",
                "Commercial photographers",
                "Law enforcement",
                "Real estate agents",
                "Infrastructure inspectors"
            ),
            counterDetectionDifficulty = "Easy: Visual, audible, Remote ID detection",
            recommendedResponse = listOf(
                "Check Remote ID apps for registration info",
                "Document time, location, behavior",
                "If persistent/suspicious, report to local police",
                "Do NOT attempt to shoot down or interfere"
            )
        ),

        DeviceType.RF_JAMMER to DeviceOperationalMetadata(
            deviceType = DeviceType.RF_JAMMER,
            estimatedRange = "GPS jammers: 5-50m typical; Cell jammers: 10-100m; WiFi: 10-50m",
            powerRequirements = "Battery (cigarette lighter adapter) or wall power",
            typicalDeploymentScenarios = listOf(
                "Criminals avoiding GPS tracking",
                "Car thieves defeating trackers",
                "Burglars disabling WiFi security",
                "Exam cheaters (illegal)",
                "Prisons (legal with federal waiver)"
            ),
            legalStatus = LegalStatus(
                generalStatus = "ILLEGAL to operate, sell, or market in the US",
                variances = "Only federal government can authorize use",
                relevantLaws = listOf(
                    "Communications Act of 1934",
                    "47 U.S.C. Section 333",
                    "FCC enforcement actions - up to \$100K+ fines",
                    "Criminal penalties possible"
                )
            ),
            dataCollected = listOf("N/A - jamming devices collect no data"),
            dataRetention = "N/A",
            typicalOperators = listOf(
                "Criminals",
                "Fleet drivers avoiding tracking (illegal)",
                "Prisons (federally authorized)"
            ),
            counterDetectionDifficulty = "Moderate: Detection by signal loss pattern analysis",
            recommendedResponse = listOf(
                "DANGER: Cannot make emergency calls in jammed area",
                "Leave the area immediately if possible",
                "Note time, location, duration for FCC report",
                "Report persistent jamming to FCC Enforcement Bureau",
                "Consider using wired alternatives"
            )
        ),

        DeviceType.STINGRAY_IMSI to DeviceOperationalMetadata(
            deviceType = DeviceType.STINGRAY_IMSI,
            estimatedRange = "Effective range: 1-2 km radius",
            powerRequirements = "High power - typically vehicle-mounted with generator",
            typicalDeploymentScenarios = listOf(
                "Law enforcement surveillance (warrant varies by jurisdiction)",
                "Mass event monitoring (protests, gatherings)",
                "Locating specific phones/individuals",
                "Foreign intelligence (illegal domestic use)"
            ),
            legalStatus = LegalStatus(
                generalStatus = "Legal for law enforcement with appropriate legal process",
                variances = "DOJ requires warrant except for exigent circumstances",
                relevantLaws = listOf(
                    "Fourth Amendment (search/seizure)",
                    "Carpenter v. United States (2018)",
                    "State cell-site simulator laws",
                    "DOJ policy requires warrant"
                )
            ),
            dataCollected = listOf(
                "IMSI (SIM identifier) of all nearby phones",
                "IMEI (device identifier)",
                "Phone calls (with active interception)",
                "SMS messages",
                "Precise location",
                "Device model information"
            ),
            dataRetention = "Varies - often destroyed after investigation",
            typicalOperators = listOf(
                "FBI",
                "DEA",
                "US Marshals",
                "State/local law enforcement",
                "Foreign intelligence (illegal in US)"
            ),
            counterDetectionDifficulty = "Difficult: Requires cellular protocol analysis",
            recommendedResponse = listOf(
                "Enable airplane mode for complete privacy",
                "Use encrypted messaging (Signal) if phone must stay on",
                "Disable 2G if phone supports it",
                "Note location/time for potential legal challenges",
                "Use WiFi calling on trusted network"
            )
        ),

        DeviceType.AIRTAG to DeviceOperationalMetadata(
            deviceType = DeviceType.AIRTAG,
            estimatedRange = "BLE: ~10-30m direct; Find My network: global",
            powerRequirements = "CR2032 battery - ~1 year lifespan",
            typicalDeploymentScenarios = listOf(
                "Personal item tracking (keys, bags)",
                "Pet tracking",
                "Vehicle tracking",
                "Stalking (illegal use)"
            ),
            legalStatus = LegalStatus(
                generalStatus = "Legal for personal property; illegal to track others without consent",
                variances = "Anti-stalking laws apply in all states",
                relevantLaws = listOf(
                    "State stalking/cyberstalking laws",
                    "Federal stalking statutes (18 U.S.C. 2261A)",
                    "Apple's anti-stalking measures"
                )
            ),
            dataCollected = listOf(
                "Real-time location via Find My network",
                "Location history (on owner's device)",
                "Timestamps of location updates"
            ),
            dataRetention = "Apple: 24 hours; Owner device: varies",
            typicalOperators = listOf(
                "Consumers tracking belongings",
                "Parents tracking children's items",
                "Stalkers/abusers (illegal)"
            ),
            counterDetectionDifficulty = "Easy: iOS alerts, Android AirGuard app, audio chirp",
            recommendedResponse = listOf(
                "If unknown AirTag found, disable it (remove battery)",
                "Use AirGuard app on Android for detection",
                "iOS will alert you to unknown AirTags traveling with you",
                "Report to police if you believe you're being stalked"
            )
        ),

        DeviceType.RING_DOORBELL to DeviceOperationalMetadata(
            deviceType = DeviceType.RING_DOORBELL,
            estimatedRange = "WiFi dependent; Audio: 15-25 feet; Video: 180 degrees",
            powerRequirements = "Battery (rechargeable) or hardwired",
            typicalDeploymentScenarios = listOf(
                "Home security",
                "Package theft prevention",
                "Visitor monitoring",
                "Neighborhood surveillance (Neighbors app)"
            ),
            legalStatus = LegalStatus(
                generalStatus = "Legal on own property; recordings of public areas generally legal",
                variances = "Audio recording laws vary by state (one-party vs all-party consent)",
                relevantLaws = listOf(
                    "State wiretapping laws (audio)",
                    "Privacy laws for public areas",
                    "Amazon/Ring law enforcement partnerships"
                )
            ),
            dataCollected = listOf(
                "Video footage (motion-triggered or continuous)",
                "Audio recordings",
                "Motion detection events",
                "Facial recognition (via Neighbors app)",
                "Visitor patterns"
            ),
            dataRetention = "Cloud: 60 days (Ring Protect); Can be shared with police",
            typicalOperators = listOf(
                "Homeowners",
                "Landlords",
                "Business owners",
                "Law enforcement (via requests)"
            ),
            counterDetectionDifficulty = "Easy: Visible device, WiFi scan",
            recommendedResponse = listOf(
                "Be aware you may be recorded approaching property",
                "Ring footage may be shared with 2,500+ police departments",
                "Audio is captured - be mindful of conversations",
                "Check if property has Ring via visible device or Neighbors app"
            )
        ),

        DeviceType.FLOCK_SAFETY_CAMERA to DeviceOperationalMetadata(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            estimatedRange = "ALPR capture: effective on passing vehicles at normal speeds",
            powerRequirements = "Solar powered with cellular backhaul",
            typicalDeploymentScenarios = listOf(
                "Neighborhood entrance monitoring",
                "HOA/private community surveillance",
                "Law enforcement vehicle tracking",
                "Business parking lot monitoring"
            ),
            legalStatus = LegalStatus(
                generalStatus = "Legal - no expectation of privacy for license plates in public",
                variances = "Some states have ALPR data retention limits",
                relevantLaws = listOf(
                    "State ALPR laws (CA, ME, NH have restrictions)",
                    "Fourth Amendment considerations",
                    "Data retention policies vary"
                )
            ),
            dataCollected = listOf(
                "License plate numbers and images",
                "Vehicle make, model, color",
                "Direction of travel",
                "Timestamps and GPS coordinates",
                "Vehicle 'fingerprint' for re-identification"
            ),
            dataRetention = "Flock: 30 days standard; Law enforcement may retain longer",
            typicalOperators = listOf(
                "HOAs and private communities",
                "Law enforcement agencies",
                "Business parks",
                "Schools"
            ),
            counterDetectionDifficulty = "Easy: Visible pole-mounted cameras, WiFi/BLE detection",
            recommendedResponse = listOf(
                "Your vehicle movements are being tracked in this area",
                "Data may be shared across 1,500+ law enforcement agencies",
                "Consider varying routes if concerned about pattern analysis",
                "Check flockos.com or local government for camera locations"
            )
        )
    )
}

package com.flockyou.detection.framework

import com.flockyou.data.model.ThreatLevel

/**
 * Comprehensive Tracker Signature Database
 *
 * Contains signatures for:
 * - BLE tracker devices (AirTag, Tile, SmartTag, etc.)
 * - Retail/advertising beacons
 * - Ultrasonic tracking beacons
 * - Sub-GHz RF trackers
 * - Known surveillance devices
 *
 * This database is designed for easy maintenance and extension.
 */
object TrackerDatabase {

    // ============================================================================
    // BLE Manufacturer IDs
    // ============================================================================

    /**
     * Expanded manufacturer ID database for tracker detection
     */
    val TRACKER_MANUFACTURERS: Map<Int, TrackerManufacturerInfo> = mapOf(
        // Major consumer tracker manufacturers
        ManufacturerIds.APPLE to TrackerManufacturerInfo(
            name = "Apple",
            products = listOf("AirTag", "Find My Accessories", "iPhone", "iPad", "MacBook"),
            trackerLikelihood = 0.7f,
            notes = "AirTags use Find My network with FD6F service UUID when separated"
        ),
        ManufacturerIds.GOOGLE to TrackerManufacturerInfo(
            name = "Google",
            products = listOf("Pixel", "Fast Pair Devices", "Nest"),
            trackerLikelihood = 0.3f,
            notes = "Fast Pair protocol for quick pairing"
        ),
        ManufacturerIds.SAMSUNG to TrackerManufacturerInfo(
            name = "Samsung",
            products = listOf("SmartTag", "SmartTag+", "Galaxy SmartTag2"),
            trackerLikelihood = 0.8f,
            notes = "SmartThings Find network"
        ),
        ManufacturerIds.TILE to TrackerManufacturerInfo(
            name = "Tile (Life360)",
            products = listOf("Tile Mate", "Tile Pro", "Tile Slim", "Tile Sticker"),
            trackerLikelihood = 0.95f,
            notes = "Dedicated tracker devices"
        ),
        ManufacturerIds.CHIPOLO to TrackerManufacturerInfo(
            name = "Chipolo",
            products = listOf("Chipolo ONE", "Chipolo ONE Spot", "Chipolo CARD"),
            trackerLikelihood = 0.95f,
            notes = "Compatible with Find My and standalone network"
        ),
        ManufacturerIds.PEBBLEBEE to TrackerManufacturerInfo(
            name = "Pebblebee",
            products = listOf("Pebblebee Clip", "Pebblebee Card", "Pebblebee Tag"),
            trackerLikelihood = 0.95f,
            notes = "Find My compatible trackers"
        ),
        ManufacturerIds.EUFY to TrackerManufacturerInfo(
            name = "Eufy (Anker)",
            products = listOf("Eufy SmartTrack Link", "Eufy SmartTrack Card"),
            trackerLikelihood = 0.95f,
            notes = "Find My compatible trackers"
        ),
        ManufacturerIds.ORBIT to TrackerManufacturerInfo(
            name = "Orbit",
            products = listOf("Orbit Keys", "Orbit Card", "Orbit Stick-On"),
            trackerLikelihood = 0.95f,
            notes = "Standalone Bluetooth trackers"
        ),

        // Retail beacon manufacturers
        ManufacturerIds.KONTAKT_IO to TrackerManufacturerInfo(
            name = "Kontakt.io",
            products = listOf("Smart Beacon", "Asset Tag", "Portal Beam"),
            trackerLikelihood = 0.6f,
            notes = "Industrial/retail beacon solutions"
        ),
        ManufacturerIds.ESTIMOTE to TrackerManufacturerInfo(
            name = "Estimote",
            products = listOf("Proximity Beacon", "Location Beacon", "Stickers"),
            trackerLikelihood = 0.7f,
            notes = "Retail analytics and proximity marketing"
        ),
        ManufacturerIds.GIMBAL to TrackerManufacturerInfo(
            name = "Gimbal",
            products = listOf("Series 10", "Series 21", "Series 22"),
            trackerLikelihood = 0.7f,
            notes = "Location-based marketing beacons"
        ),
        ManufacturerIds.RADIUS_NETWORKS to TrackerManufacturerInfo(
            name = "Radius Networks",
            products = listOf("RadBeacon", "RadBeacon USB", "RadBeacon Dot"),
            trackerLikelihood = 0.6f,
            notes = "iBeacon and Eddystone compatible"
        ),
        ManufacturerIds.BLUVISION to TrackerManufacturerInfo(
            name = "BluVision (HID Global)",
            products = listOf("BEEKs", "BluFi"),
            trackerLikelihood = 0.6f,
            notes = "Enterprise asset tracking"
        ),

        // IoT/Smart Home
        ManufacturerIds.AMAZON to TrackerManufacturerInfo(
            name = "Amazon",
            products = listOf("Echo", "Ring", "Sidewalk Bridge"),
            trackerLikelihood = 0.2f,
            notes = "Amazon Sidewalk mesh network capability"
        ),
        ManufacturerIds.MICROSOFT to TrackerManufacturerInfo(
            name = "Microsoft",
            products = listOf("Surface", "Xbox Controller"),
            trackerLikelihood = 0.1f,
            notes = "Swift Pair protocol"
        ),

        // Vehicle/Fleet trackers
        ManufacturerIds.GARMIN to TrackerManufacturerInfo(
            name = "Garmin",
            products = listOf("InReach", "DriveSmart", "Dash Cam"),
            trackerLikelihood = 0.3f,
            notes = "GPS tracking devices"
        ),
        ManufacturerIds.SPYTEC to TrackerManufacturerInfo(
            name = "SpyTec",
            products = listOf("GL300", "STI GL300", "M2"),
            trackerLikelihood = 0.95f,
            notes = "Dedicated GPS trackers - often used covertly"
        ),
        ManufacturerIds.BOUNCIE to TrackerManufacturerInfo(
            name = "Bouncie",
            products = listOf("GPS Car Tracker"),
            trackerLikelihood = 0.9f,
            notes = "OBD-II vehicle trackers"
        ),
        ManufacturerIds.VYNCS to TrackerManufacturerInfo(
            name = "Vyncs",
            products = listOf("GPS Tracker"),
            trackerLikelihood = 0.9f,
            notes = "Connected car tracking"
        ),

        // Security/Surveillance
        ManufacturerIds.WYZE to TrackerManufacturerInfo(
            name = "Wyze",
            products = listOf("Wyze Cam", "Wyze Lock", "Wyze Sense"),
            trackerLikelihood = 0.3f,
            notes = "Smart home security devices"
        ),
        ManufacturerIds.ARLO to TrackerManufacturerInfo(
            name = "Arlo",
            products = listOf("Arlo Pro", "Arlo Ultra", "Arlo Essential"),
            trackerLikelihood = 0.3f,
            notes = "Security camera systems"
        ),
        ManufacturerIds.RING_AMAZON to TrackerManufacturerInfo(
            name = "Ring (Amazon)",
            products = listOf("Ring Doorbell", "Ring Spotlight", "Ring Floodlight"),
            trackerLikelihood = 0.3f,
            notes = "Video doorbell and security"
        )
    )

    /**
     * Tracker manufacturer info
     */
    data class TrackerManufacturerInfo(
        val name: String,
        val products: List<String>,
        val trackerLikelihood: Float,  // 0-1, how likely is this a dedicated tracker
        val notes: String
    )

    // ============================================================================
    // BLE Service UUIDs for Tracker Detection
    // ============================================================================

    /**
     * Service UUIDs that indicate tracking capability
     */
    val TRACKER_SERVICE_UUIDS: Map<String, ServiceUuidInfo> = mapOf(
        // Apple Find My Network
        "7DFC9000-7D1C-4951-86AA-8D9728F8D66C" to ServiceUuidInfo(
            name = "Apple Find My Network",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Device participates in Apple's Find My crowdsourced tracking network"
        ),

        // Exposure Notification / Unwanted Tracking Alert
        "0000FD6F-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Exposure Notification / Unwanted Tracking",
            category = TrackerCategory.COVERT_TRACKER,
            threatLevel = ThreatLevel.CRITICAL,
            description = "AirTag or Find My device separated from owner - potential stalking"
        ),

        // Tile tracker
        "0000FEED-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Tile Tracker",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Tile Bluetooth tracker detected"
        ),
        "0000FE5A-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Tile Tracker (Alt)",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Tile Bluetooth tracker detected"
        ),

        // Samsung SmartThings
        "0000FD5A-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Samsung SmartTag",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Samsung SmartTag tracker detected"
        ),

        // Eddystone beacon
        "0000FEAA-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Eddystone Beacon",
            category = TrackerCategory.PROXIMITY_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Google Eddystone beacon - may be used for tracking or retail"
        ),

        // Chipolo
        "0000FE33-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Chipolo Tracker",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Chipolo Bluetooth tracker detected"
        ),

        // Amazon Sidewalk
        "0000FE52-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Amazon Sidewalk",
            category = TrackerCategory.PROXIMITY_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Amazon Sidewalk mesh network device"
        ),

        // Google Fast Pair
        "0000FE2C-0000-1000-8000-00805F9B34FB" to ServiceUuidInfo(
            name = "Google Fast Pair",
            category = TrackerCategory.UNKNOWN,
            threatLevel = ThreatLevel.LOW,
            description = "Google Fast Pair service - quick Bluetooth pairing"
        )
    )

    /**
     * Service UUID info
     */
    data class ServiceUuidInfo(
        val name: String,
        val category: TrackerCategory,
        val threatLevel: ThreatLevel,
        val description: String
    )

    // ============================================================================
    // BLE Tracker Signatures
    // ============================================================================

    /**
     * Complete BLE tracker signature database
     */
    val BLE_TRACKER_SIGNATURES: List<BleTrackerSignature> = listOf(
        // Apple AirTag
        BleTrackerSignature(
            id = "apple_airtag",
            name = "Apple AirTag",
            manufacturer = "Apple",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Apple AirTag Bluetooth tracker with Find My network",
            manufacturerIds = setOf(ManufacturerIds.APPLE),
            serviceUuids = setOf(
                "7DFC9000-7D1C-4951-86AA-8D9728F8D66C",  // Find My
                "0000FD6F-0000-1000-8000-00805F9B34FB"   // Unwanted tracking alert
            ),
            beaconProtocol = BeaconProtocolType.FIND_MY,
            usesRandomAddress = true,
            rotatesAddress = true,
            addressRotationIntervalMs = 15 * 60 * 1000L  // ~15 minutes
        ),

        // Samsung SmartTag
        BleTrackerSignature(
            id = "samsung_smarttag",
            name = "Samsung SmartTag",
            manufacturer = "Samsung",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Samsung SmartTag Bluetooth tracker with SmartThings Find",
            manufacturerIds = setOf(ManufacturerIds.SAMSUNG),
            serviceUuids = setOf("0000FD5A-0000-1000-8000-00805F9B34FB"),
            usesRandomAddress = true,
            rotatesAddress = true
        ),

        // Samsung SmartTag2
        BleTrackerSignature(
            id = "samsung_smarttag2",
            name = "Samsung SmartTag2",
            manufacturer = "Samsung",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Samsung SmartTag2 with UWB and SmartThings Find",
            manufacturerIds = setOf(ManufacturerIds.SAMSUNG),
            serviceUuids = setOf("0000FD5A-0000-1000-8000-00805F9B34FB"),
            usesRandomAddress = true,
            rotatesAddress = true
        ),

        // Tile trackers
        BleTrackerSignature(
            id = "tile_tracker",
            name = "Tile Tracker",
            manufacturer = "Tile (Life360)",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Tile Bluetooth tracker",
            manufacturerIds = setOf(ManufacturerIds.TILE),
            serviceUuids = setOf(
                "0000FEED-0000-1000-8000-00805F9B34FB",
                "0000FE5A-0000-1000-8000-00805F9B34FB"
            ),
            namePatterns = listOf(
                Regex("^Tile.*", RegexOption.IGNORE_CASE)
            ),
            usesRandomAddress = false  // Tile uses static addresses
        ),

        // Chipolo
        BleTrackerSignature(
            id = "chipolo_tracker",
            name = "Chipolo Tracker",
            manufacturer = "Chipolo",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Chipolo Bluetooth tracker",
            manufacturerIds = setOf(ManufacturerIds.CHIPOLO),
            serviceUuids = setOf("0000FE33-0000-1000-8000-00805F9B34FB"),
            namePatterns = listOf(
                Regex("^Chipolo.*", RegexOption.IGNORE_CASE)
            ),
            usesRandomAddress = true,
            rotatesAddress = true
        ),

        // Pebblebee
        BleTrackerSignature(
            id = "pebblebee_tracker",
            name = "Pebblebee Tracker",
            manufacturer = "Pebblebee",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Pebblebee Bluetooth tracker with Find My support",
            manufacturerIds = setOf(ManufacturerIds.PEBBLEBEE),
            serviceUuids = setOf("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"),
            namePatterns = listOf(
                Regex("^Pebblebee.*", RegexOption.IGNORE_CASE),
                Regex("^PB.*", RegexOption.IGNORE_CASE)
            ),
            usesRandomAddress = true,
            rotatesAddress = true
        ),

        // Eufy SmartTrack
        BleTrackerSignature(
            id = "eufy_smarttrack",
            name = "Eufy SmartTrack",
            manufacturer = "Eufy (Anker)",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Eufy SmartTrack with Find My support",
            manufacturerIds = setOf(ManufacturerIds.EUFY),
            serviceUuids = setOf("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"),
            namePatterns = listOf(
                Regex("^eufy.*", RegexOption.IGNORE_CASE),
                Regex("^SmartTrack.*", RegexOption.IGNORE_CASE)
            ),
            usesRandomAddress = true,
            rotatesAddress = true
        ),

        // Generic Find My accessory
        BleTrackerSignature(
            id = "generic_findmy",
            name = "Find My Compatible Tracker",
            manufacturer = "Various",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Apple Find My network compatible tracker",
            serviceUuids = setOf("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"),
            usesRandomAddress = true,
            rotatesAddress = true
        ),

        // Retail iBeacon
        BleTrackerSignature(
            id = "retail_ibeacon",
            name = "Retail iBeacon",
            manufacturer = "Various",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Apple iBeacon format retail/proximity beacon",
            manufacturerIds = setOf(ManufacturerIds.APPLE),
            beaconProtocol = BeaconProtocolType.IBEACON,
            usesRandomAddress = false
        ),

        // Eddystone Beacon
        BleTrackerSignature(
            id = "eddystone_beacon",
            name = "Eddystone Beacon",
            manufacturer = "Various",
            category = TrackerCategory.PROXIMITY_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Google Eddystone format beacon",
            serviceUuids = setOf("0000FEAA-0000-1000-8000-00805F9B34FB"),
            beaconProtocol = BeaconProtocolType.EDDYSTONE_UID,
            usesRandomAddress = false
        ),

        // Kontakt.io
        BleTrackerSignature(
            id = "kontakt_beacon",
            name = "Kontakt.io Beacon",
            manufacturer = "Kontakt.io",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Kontakt.io industrial/retail beacon",
            manufacturerIds = setOf(ManufacturerIds.KONTAKT_IO),
            namePatterns = listOf(
                Regex("^Kontakt.*", RegexOption.IGNORE_CASE),
                Regex("^KTK.*", RegexOption.IGNORE_CASE)
            )
        ),

        // Estimote
        BleTrackerSignature(
            id = "estimote_beacon",
            name = "Estimote Beacon",
            manufacturer = "Estimote",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Estimote proximity beacon",
            manufacturerIds = setOf(ManufacturerIds.ESTIMOTE),
            namePatterns = listOf(
                Regex("^Estimote.*", RegexOption.IGNORE_CASE)
            )
        ),

        // Amazon Sidewalk
        BleTrackerSignature(
            id = "amazon_sidewalk",
            name = "Amazon Sidewalk Device",
            manufacturer = "Amazon",
            category = TrackerCategory.PROXIMITY_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Amazon Sidewalk mesh network participant",
            manufacturerIds = setOf(ManufacturerIds.AMAZON),
            serviceUuids = setOf("0000FE52-0000-1000-8000-00805F9B34FB")
        )
    )

    // ============================================================================
    // Ultrasonic Beacon Signatures (Expanded)
    // ============================================================================

    /**
     * Comprehensive ultrasonic beacon signature database
     *
     * Real-world ultrasonic tracking knowledge:
     * - Cross-device tracking links phone, tablet, laptop, smart TV
     * - De-anonymization can link anonymous browsing to real identity
     * - Location history tracks store visits and time spent
     * - Ad attribution knows which TV ad made you buy
     * - Household mapping identifies all devices in home
     *
     * Even "opt-in" services often bury consent in ToS, share data
     * with dozens of partners, and persist tracking after app uninstall.
     */
    val ULTRASONIC_SIGNATURES: List<UltrasonicTrackerSignature> = listOf(
        // ============================================================
        // SilverPush (India) - Cross-device ad tracking
        // SDK was in 200+ apps (2015-2017), supposedly discontinued
        // Beacon duration: 2-5 seconds
        // ============================================================
        UltrasonicTrackerSignature(
            id = "silverpush_primary",
            name = "SilverPush Primary",
            manufacturer = "SilverPush Technologies (India)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "SilverPush cross-device ad tracking. SDK was found in 200+ apps (2015-2017). " +
                "Links your phone to TV ads using FSK modulation. Beacon typically lasts 2-5 seconds.",
            primaryFrequencyHz = 18000,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING,
            modulationType = UltrasonicModulation.FSK,
            beaconDurationMs = 2000L to 5000L,
            privacyImpact = PrivacyImpact.CROSS_DEVICE_LINKING,
            legalStatus = LegalStatus.FTC_INVESTIGATED,
            confirmationMethod = "Check if detection correlates with TV commercials. " +
                "Use another phone with ultrasonic app to cross-verify.",
            mitigationAdvice = "Check app permissions for microphone access. " +
                "Revoke mic permission from apps that don't need it. Mute TV during commercials."
        ),
        UltrasonicTrackerSignature(
            id = "silverpush_secondary",
            name = "SilverPush Secondary",
            manufacturer = "SilverPush Technologies (India)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "SilverPush secondary beacon frequency. Part of multi-frequency encoding scheme.",
            primaryFrequencyHz = 18200,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING,
            modulationType = UltrasonicModulation.FSK,
            beaconDurationMs = 2000L to 5000L,
            privacyImpact = PrivacyImpact.CROSS_DEVICE_LINKING,
            legalStatus = LegalStatus.FTC_INVESTIGATED
        ),

        // ============================================================
        // Alphonso (US) - TV ad attribution / "Automated Content Recognition"
        // Found in 1,000+ apps including games
        // Always-on listening in background
        // FTC investigated in 2018
        // ============================================================
        UltrasonicTrackerSignature(
            id = "alphonso_tv",
            name = "Alphonso TV Attribution",
            manufacturer = "Alphonso Inc (US)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Alphonso Automated Content Recognition. Found in 1,000+ apps including games. " +
                "Always-on background listening fingerprints TV audio to track shows and ads you watch. " +
                "FTC investigated in 2018.",
            primaryFrequencyHz = 18500,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            modulationType = UltrasonicModulation.PSK,
            privacyImpact = PrivacyImpact.VIEWING_HABITS,
            legalStatus = LegalStatus.FTC_INVESTIGATED,
            confirmationMethod = "Check if detection correlates with TV being on. " +
                "Signal should appear when specific commercials air.",
            mitigationAdvice = "Revoke microphone permissions from game apps and apps that shouldn't need audio. " +
                "Use F-Droid or privacy-focused app stores."
        ),

        // ============================================================
        // Zapr Media Labs - TV content recognition (India)
        // ============================================================
        UltrasonicTrackerSignature(
            id = "zapr_tv",
            name = "Zapr TV Attribution",
            manufacturer = "Zapr Media Labs (India)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Zapr TV content recognition. Tracks what TV shows you watch for targeted advertising.",
            primaryFrequencyHz = 17500,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            privacyImpact = PrivacyImpact.VIEWING_HABITS,
            confirmationMethod = "Check if detection happens when TV is playing content.",
            mitigationAdvice = "Mute TV during commercials. Check installed apps for Zapr SDK."
        ),

        // ============================================================
        // Signal360 - Location-based advertising
        // Deployed in malls, airports
        // ============================================================
        UltrasonicTrackerSignature(
            id = "signal360",
            name = "Signal360",
            manufacturer = "Signal360 (US)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Signal360 proximity marketing. Deployed in malls and airports for location-based advertising.",
            primaryFrequencyHz = 19000,
            trackingPurpose = UltrasonicTrackingPurpose.LOCATION_VERIFICATION,
            privacyImpact = PrivacyImpact.LOCATION_TRACKING,
            confirmationMethod = "Detection should occur near mall entrances or airport gates. " +
                "Move away from suspected source - signal should drop.",
            mitigationAdvice = "Disable microphone for shopping apps when not actively using voice features."
        ),

        // ============================================================
        // LISNR (US) - Cross-device linking / proximity payments
        // Higher bandwidth, legitimate use cases exist (check-in, payments)
        // ============================================================
        UltrasonicTrackerSignature(
            id = "lisnr",
            name = "LISNR",
            manufacturer = "LISNR Inc (US)",
            category = TrackerCategory.CROSS_DEVICE_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "LISNR ultrasonic data transfer. Used for proximity payments, ticketing, and cross-device linking. " +
                "Higher bandwidth than other systems. Some legitimate uses exist (check-in, payments).",
            primaryFrequencyHz = 19500,
            trackingPurpose = UltrasonicTrackingPurpose.CROSS_DEVICE_LINKING,
            modulationType = UltrasonicModulation.CHIRP,
            privacyImpact = PrivacyImpact.CROSS_DEVICE_LINKING,
            hasLegitimateUses = true,
            confirmationMethod = "May be legitimate if at ticketing event or using payment app. " +
                "Suspicious if detected in random locations without clear source.",
            mitigationAdvice = "If not using for payments/ticketing, revoke mic access from apps. " +
                "Consider ultrasonic blocking apps."
        ),

        // ============================================================
        // Shopkick - Retail presence detection
        // Deployed in Target, Macy's, Best Buy, etc.
        // User opt-in (mostly legitimate)
        // ============================================================
        UltrasonicTrackerSignature(
            id = "shopkick",
            name = "Shopkick",
            manufacturer = "Shopkick/SK Telecom",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Shopkick retail presence detection and loyalty rewards. " +
                "Deployed in Target, Macy's, Best Buy, and other major retailers. " +
                "Usually user opt-in for rewards program.",
            primaryFrequencyHz = 20000,
            trackingPurpose = UltrasonicTrackingPurpose.RETAIL_ANALYTICS,
            privacyImpact = PrivacyImpact.LOCATION_TRACKING,
            hasLegitimateUses = true,
            deploymentLocations = listOf("Target", "Macy's", "Best Buy", "Walmart", "CVS"),
            confirmationMethod = "Note if detection happens near store entrance. " +
                "Check if you have Shopkick app installed.",
            mitigationAdvice = "Disable Shopkick app if you don't want retail tracking. " +
                "The app must be installed and have mic permission for this to affect you."
        ),

        // ============================================================
        // Realeyes - Ad attention measurement
        // ============================================================
        UltrasonicTrackerSignature(
            id = "realeyes",
            name = "Realeyes Attention Tracking",
            manufacturer = "Realeyes",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Realeyes ad attention measurement. Measures if you're paying attention to advertisements.",
            primaryFrequencyHz = 19200,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING,
            privacyImpact = PrivacyImpact.VIEWING_HABITS
        ),

        // ============================================================
        // TVision - TV viewership measurement
        // ============================================================
        UltrasonicTrackerSignature(
            id = "tvision",
            name = "TVision Viewership",
            manufacturer = "TVision Insights",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "TVision TV viewership measurement. Tracks what you watch and for how long.",
            primaryFrequencyHz = 19800,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            privacyImpact = PrivacyImpact.VIEWING_HABITS,
            confirmationMethod = "Detection should correlate with TV being powered on."
        ),

        // ============================================================
        // Samba TV / Inscape - Smart TV ACR
        // Built into Samsung, Vizio, LG smart TVs
        // Tracks everything you watch
        // ============================================================
        UltrasonicTrackerSignature(
            id = "samba_tv",
            name = "Samba TV ACR",
            manufacturer = "Samba TV",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Samba TV Automatic Content Recognition. Built into many smart TVs. " +
                "Tracks everything you watch including streaming, cable, gaming, and HDMI inputs.",
            primaryFrequencyHz = 20200,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            privacyImpact = PrivacyImpact.VIEWING_HABITS,
            deploymentLocations = listOf("Samsung Smart TVs", "Vizio Smart TVs", "LG Smart TVs", "Sony Smart TVs"),
            confirmationMethod = "Check smart TV settings for 'Viewing Data' or 'Samba Interactive TV' options. " +
                "At home + smart TV on = likely Samba/Inscape (lower external threat).",
            mitigationAdvice = "Disable ACR in smart TV settings. Look for 'Viewing Data', 'Smart Interactivity', " +
                "or 'Samba Interactive TV' options. Consider using external streaming device instead."
        ),
        UltrasonicTrackerSignature(
            id = "samba_tv_secondary",
            name = "Samba TV ACR (High Band)",
            manufacturer = "Samba TV",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Samba TV ACR secondary frequency band (20-21 kHz range).",
            primaryFrequencyHz = 20800,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            privacyImpact = PrivacyImpact.VIEWING_HABITS
        ),

        // Inscape (Vizio)
        UltrasonicTrackerSignature(
            id = "inscape",
            name = "Inscape Smart TV",
            manufacturer = "Inscape (Vizio)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Inscape smart TV viewing data collection. Vizio paid $2.2M FTC settlement in 2017 " +
                "for collecting viewing data without consent.",
            primaryFrequencyHz = 21500,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            privacyImpact = PrivacyImpact.VIEWING_HABITS,
            legalStatus = LegalStatus.FTC_SETTLED,
            confirmationMethod = "Check if you have a Vizio TV. Look for 'Smart Interactivity' setting.",
            mitigationAdvice = "Disable 'Smart Interactivity' in Vizio TV settings."
        ),

        // Data Plus Math
        UltrasonicTrackerSignature(
            id = "data_plus_math",
            name = "Data Plus Math Attribution",
            manufacturer = "LiveRamp (Data Plus Math)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Cross-platform ad attribution. Links TV ad exposure to online purchases and app installs.",
            primaryFrequencyHz = 22000,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING,
            privacyImpact = PrivacyImpact.CROSS_DEVICE_LINKING,
            confirmationMethod = "Check if detection correlates with specific ad campaigns."
        ),

        // Generic retail beacon bands
        UltrasonicTrackerSignature(
            id = "retail_band_1",
            name = "Retail Beacon Band 1",
            manufacturer = "Unknown",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Generic retail location beacon. Common in shopping malls and large retail stores.",
            primaryFrequencyHz = 20500,
            trackingPurpose = UltrasonicTrackingPurpose.RETAIL_ANALYTICS,
            privacyImpact = PrivacyImpact.LOCATION_TRACKING,
            confirmationMethod = "Note if detected in retail environment. Move away to verify signal drops."
        ),
        UltrasonicTrackerSignature(
            id = "retail_band_2",
            name = "Retail Beacon Band 2",
            manufacturer = "Unknown",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Generic retail presence detection.",
            primaryFrequencyHz = 21000,
            trackingPurpose = UltrasonicTrackingPurpose.PRESENCE_DETECTION,
            privacyImpact = PrivacyImpact.LOCATION_TRACKING
        )
    )

    // ============================================================================
    // Sub-GHz RF Tracker Signatures
    // ============================================================================

    /**
     * Sub-GHz RF tracker frequency database
     */
    val RF_TRACKER_SIGNATURES: List<RfTrackerSignature> = listOf(
        // GPS Tracker frequencies
        RfTrackerSignature(
            id = "gps_tracker_433",
            name = "GPS Tracker (433 MHz)",
            manufacturer = "Various",
            category = TrackerCategory.GPS_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Common GPS tracker uplink frequency",
            frequencyRanges = listOf(
                FrequencyRange(433_920_000L, 2_000_000L, "EU ISM band")
            ),
            modulationType = RfModulation.FSK,
            regions = setOf(RfRegion.EUROPE, RfRegion.ASIA_PACIFIC)
        ),
        RfTrackerSignature(
            id = "gps_tracker_868",
            name = "GPS Tracker (868 MHz)",
            manufacturer = "Various",
            category = TrackerCategory.GPS_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "European GPS tracker frequency",
            frequencyRanges = listOf(
                FrequencyRange(868_350_000L, 2_000_000L, "EU SRD band")
            ),
            modulationType = RfModulation.FSK,
            regions = setOf(RfRegion.EUROPE)
        ),
        RfTrackerSignature(
            id = "gps_tracker_915",
            name = "GPS Tracker (915 MHz)",
            manufacturer = "Various",
            category = TrackerCategory.GPS_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "US GPS tracker frequency",
            frequencyRanges = listOf(
                FrequencyRange(915_000_000L, 26_000_000L, "US ISM band")
            ),
            modulationType = RfModulation.FSK,
            regions = setOf(RfRegion.NORTH_AMERICA)
        ),

        // LoRa trackers
        RfTrackerSignature(
            id = "lora_tracker_eu",
            name = "LoRa Tracker (EU)",
            manufacturer = "Various",
            category = TrackerCategory.GPS_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "LoRaWAN tracker (European frequency)",
            frequencyRanges = listOf(
                FrequencyRange(868_100_000L, 125_000L, "LoRa EU868 CH1"),
                FrequencyRange(868_300_000L, 125_000L, "LoRa EU868 CH2"),
                FrequencyRange(868_500_000L, 125_000L, "LoRa EU868 CH3")
            ),
            modulationType = RfModulation.LORA,
            regions = setOf(RfRegion.EUROPE)
        ),
        RfTrackerSignature(
            id = "lora_tracker_us",
            name = "LoRa Tracker (US)",
            manufacturer = "Various",
            category = TrackerCategory.GPS_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "LoRaWAN tracker (US frequency)",
            frequencyRanges = listOf(
                FrequencyRange(903_900_000L, 125_000L, "LoRa US915 uplink"),
                FrequencyRange(904_100_000L, 125_000L, "LoRa US915 uplink"),
                FrequencyRange(916_800_000L, 500_000L, "LoRa US915 downlink")
            ),
            modulationType = RfModulation.LORA,
            regions = setOf(RfRegion.NORTH_AMERICA)
        ),

        // Tile (RF backup)
        RfTrackerSignature(
            id = "tile_rf",
            name = "Tile RF Beacon",
            manufacturer = "Tile (Life360)",
            category = TrackerCategory.PERSONAL_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "Tile tracker RF beacon mode",
            frequencyRanges = listOf(
                FrequencyRange(433_920_000L, 1_000_000L)
            ),
            modulationType = RfModulation.OOK
        ),

        // Vehicle key fob (potential tracking indicator)
        RfTrackerSignature(
            id = "vehicle_keyfob",
            name = "Vehicle Key Fob",
            manufacturer = "Various",
            category = TrackerCategory.VEHICLE_TRACKER,
            threatLevel = ThreatLevel.LOW,
            description = "Vehicle remote key fob - repeated signals may indicate cloning attempt",
            frequencyRanges = listOf(
                FrequencyRange(315_000_000L, 1_000_000L, "US key fob"),
                FrequencyRange(433_920_000L, 1_000_000L, "EU key fob")
            ),
            modulationType = RfModulation.ASK,
            protocolId = 2,  // KeeLoq
            protocolName = "KeeLoq"
        ),

        // Generic Sub-GHz tracker
        RfTrackerSignature(
            id = "generic_subghz_tracker",
            name = "Unknown Sub-GHz Tracker",
            manufacturer = "Unknown",
            category = TrackerCategory.RF_TRACKER,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Unknown device transmitting on tracker frequencies",
            frequencyRanges = listOf(
                FrequencyRange(300_000_000L, 50_000_000L, "300 MHz band"),
                FrequencyRange(433_920_000L, 5_000_000L, "433 MHz band"),
                FrequencyRange(868_000_000L, 10_000_000L, "868 MHz band"),
                FrequencyRange(915_000_000L, 28_000_000L, "915 MHz band")
            ),
            modulationType = RfModulation.UNKNOWN
        )
    )

    // ============================================================================
    // Lookup Functions
    // ============================================================================

    /**
     * Find BLE tracker signature by manufacturer ID
     */
    fun findByManufacturerId(manufacturerId: Int): List<BleTrackerSignature> {
        return BLE_TRACKER_SIGNATURES.filter { signature ->
            signature.manufacturerIds.contains(manufacturerId)
        }
    }

    /**
     * Find BLE tracker signature by service UUID
     */
    fun findByServiceUuid(uuid: String): List<BleTrackerSignature> {
        val normalizedUuid = uuid.uppercase()
        return BLE_TRACKER_SIGNATURES.filter { signature ->
            signature.serviceUuids.any { it.uppercase().contains(normalizedUuid) }
        }
    }

    /**
     * Find BLE tracker signature by device name
     */
    fun findByDeviceName(name: String): List<BleTrackerSignature> {
        return BLE_TRACKER_SIGNATURES.filter { signature ->
            signature.namePatterns.any { pattern -> pattern.matches(name) }
        }
    }

    /**
     * Find ultrasonic signature by frequency
     */
    fun findUltrasonicByFrequency(frequencyHz: Int, toleranceHz: Int = 100): UltrasonicTrackerSignature? {
        return ULTRASONIC_SIGNATURES.find { signature ->
            kotlin.math.abs(signature.primaryFrequencyHz - frequencyHz) <= toleranceHz ||
            signature.secondaryFrequencies.any { freq ->
                kotlin.math.abs(freq - frequencyHz) <= toleranceHz
            }
        }
    }

    /**
     * Find RF signature by frequency
     */
    fun findRfByFrequency(frequencyHz: Long): List<RfTrackerSignature> {
        return RF_TRACKER_SIGNATURES.filter { signature ->
            signature.frequencyRanges.any { range -> range.contains(frequencyHz) }
        }
    }

    /**
     * Get manufacturer info by ID
     */
    fun getManufacturerInfo(manufacturerId: Int): TrackerManufacturerInfo? {
        return TRACKER_MANUFACTURERS[manufacturerId]
    }

    /**
     * Get service UUID info
     */
    fun getServiceUuidInfo(uuid: String): ServiceUuidInfo? {
        val normalizedUuid = uuid.uppercase()
        return TRACKER_SERVICE_UUIDS.entries.find {
            it.key.uppercase() == normalizedUuid
        }?.value
    }

    /**
     * Check if a manufacturer ID belongs to a known tracker manufacturer
     */
    fun isKnownTrackerManufacturer(manufacturerId: Int): Boolean {
        val info = TRACKER_MANUFACTURERS[manufacturerId]
        return info != null && info.trackerLikelihood >= 0.7f
    }

    /**
     * Get all ultrasonic frequencies to monitor
     */
    fun getAllUltrasonicFrequencies(): List<Int> {
        return ULTRASONIC_SIGNATURES.flatMap { signature ->
            listOf(signature.primaryFrequencyHz) + signature.secondaryFrequencies
        }.distinct().sorted()
    }

    /**
     * Get all RF tracker frequency ranges
     */
    fun getAllRfTrackerFrequencies(): List<FrequencyRange> {
        return RF_TRACKER_SIGNATURES.flatMap { it.frequencyRanges }.distinctBy { it.centerHz }
    }
}

/**
 * Common BLE manufacturer IDs
 */
object ManufacturerIds {
    // Major tech companies
    const val APPLE = 0x004C
    const val GOOGLE = 0x00E0
    const val SAMSUNG = 0x0075
    const val MICROSOFT = 0x0006
    const val AMAZON = 0x0171

    // Tracker manufacturers
    const val TILE = 0x00C7
    const val CHIPOLO = 0x01DA
    const val PEBBLEBEE = 0x0822
    const val EUFY = 0x038F
    const val ORBIT = 0x0200

    // Beacon/IoT manufacturers
    const val KONTAKT_IO = 0x004D
    const val ESTIMOTE = 0x015D
    const val GIMBAL = 0x0086
    const val RADIUS_NETWORKS = 0x0118
    const val BLUVISION = 0x0105

    // Security/Surveillance
    const val WYZE = 0x0A3F
    const val ARLO = 0x02D0
    const val RING_AMAZON = 0x0171  // Same as Amazon

    // Vehicle tracking
    const val GARMIN = 0x0087
    const val SPYTEC = 0x0350
    const val BOUNCIE = 0x0410
    const val VYNCS = 0x0420
}

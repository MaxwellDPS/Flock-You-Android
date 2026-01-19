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
     */
    val ULTRASONIC_SIGNATURES: List<UltrasonicTrackerSignature> = listOf(
        // SilverPush
        UltrasonicTrackerSignature(
            id = "silverpush_primary",
            name = "SilverPush Primary",
            manufacturer = "SilverPush Technologies",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "SilverPush cross-device ad tracking beacon",
            primaryFrequencyHz = 18000,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING,
            modulationType = UltrasonicModulation.FSK
        ),
        UltrasonicTrackerSignature(
            id = "silverpush_secondary",
            name = "SilverPush Secondary",
            manufacturer = "SilverPush Technologies",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "SilverPush secondary beacon frequency",
            primaryFrequencyHz = 18200,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
        ),

        // Alphonso
        UltrasonicTrackerSignature(
            id = "alphonso_tv",
            name = "Alphonso TV Attribution",
            manufacturer = "Alphonso Inc",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Alphonso TV viewing attribution beacon",
            primaryFrequencyHz = 18500,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION,
            modulationType = UltrasonicModulation.PSK
        ),

        // Zapr
        UltrasonicTrackerSignature(
            id = "zapr_tv",
            name = "Zapr TV Attribution",
            manufacturer = "Zapr Media Labs",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Zapr TV content recognition beacon",
            primaryFrequencyHz = 17500,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
        ),

        // Signal360
        UltrasonicTrackerSignature(
            id = "signal360",
            name = "Signal360",
            manufacturer = "Signal360",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Signal360 proximity marketing beacon",
            primaryFrequencyHz = 19000,
            trackingPurpose = UltrasonicTrackingPurpose.LOCATION_VERIFICATION
        ),

        // LISNR
        UltrasonicTrackerSignature(
            id = "lisnr",
            name = "LISNR",
            manufacturer = "LISNR Inc",
            category = TrackerCategory.CROSS_DEVICE_TRACKER,
            threatLevel = ThreatLevel.HIGH,
            description = "LISNR ultrasonic data transmission",
            primaryFrequencyHz = 19500,
            trackingPurpose = UltrasonicTrackingPurpose.CROSS_DEVICE_LINKING,
            modulationType = UltrasonicModulation.CHIRP
        ),

        // Shopkick
        UltrasonicTrackerSignature(
            id = "shopkick",
            name = "Shopkick",
            manufacturer = "Shopkick/SK Telecom",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Shopkick retail location verification",
            primaryFrequencyHz = 20000,
            trackingPurpose = UltrasonicTrackingPurpose.RETAIL_ANALYTICS
        ),

        // Realeyes
        UltrasonicTrackerSignature(
            id = "realeyes",
            name = "Realeyes Attention Tracking",
            manufacturer = "Realeyes",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Realeyes ad attention measurement",
            primaryFrequencyHz = 19200,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
        ),

        // TVision
        UltrasonicTrackerSignature(
            id = "tvision",
            name = "TVision Viewership",
            manufacturer = "TVision Insights",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "TVision TV viewership measurement",
            primaryFrequencyHz = 19800,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
        ),

        // Samba TV
        UltrasonicTrackerSignature(
            id = "samba_tv",
            name = "Samba TV ACR",
            manufacturer = "Samba TV",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Samba TV automatic content recognition",
            primaryFrequencyHz = 20200,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
        ),

        // Inscape (Vizio)
        UltrasonicTrackerSignature(
            id = "inscape",
            name = "Inscape Smart TV",
            manufacturer = "Inscape (Vizio)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Inscape smart TV viewing data",
            primaryFrequencyHz = 21500,
            trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
        ),

        // Data Plus Math
        UltrasonicTrackerSignature(
            id = "data_plus_math",
            name = "Data Plus Math Attribution",
            manufacturer = "LiveRamp (Data Plus Math)",
            category = TrackerCategory.ADVERTISING_BEACON,
            threatLevel = ThreatLevel.HIGH,
            description = "Cross-platform ad attribution",
            primaryFrequencyHz = 22000,
            trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
        ),

        // Generic retail beacon bands
        UltrasonicTrackerSignature(
            id = "retail_band_1",
            name = "Retail Beacon Band 1",
            manufacturer = "Unknown",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Generic retail location beacon",
            primaryFrequencyHz = 20500,
            trackingPurpose = UltrasonicTrackingPurpose.RETAIL_ANALYTICS
        ),
        UltrasonicTrackerSignature(
            id = "retail_band_2",
            name = "Retail Beacon Band 2",
            manufacturer = "Unknown",
            category = TrackerCategory.RETAIL_BEACON,
            threatLevel = ThreatLevel.MEDIUM,
            description = "Generic retail presence detection",
            primaryFrequencyHz = 21000,
            trackingPurpose = UltrasonicTrackingPurpose.PRESENCE_DETECTION
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
    const val TILE = 0x0157
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

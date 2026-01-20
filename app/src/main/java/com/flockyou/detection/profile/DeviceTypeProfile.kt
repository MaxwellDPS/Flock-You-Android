package com.flockyou.detection.profile

import com.flockyou.data.model.DeviceType

/**
 * Centralized device type profile system.
 * Contains comprehensive information about each surveillance device type including:
 * - Device descriptions and categories
 * - Privacy impact assessments
 * - Data collection capabilities
 * - Legal frameworks
 * - Actionable recommendations
 * - AI prompt templates for LLM analysis
 * - Threat scoring with contextual modifiers
 */

// ==================== ENUMS ====================

/**
 * Privacy impact level for surveillance devices.
 */
enum class PrivacyImpact(val displayName: String, val description: String) {
    CRITICAL("Critical", "Immediate and severe privacy violation - active interception or invasive surveillance"),
    HIGH("High", "Significant privacy concerns - persistent tracking or recording of personal data"),
    MEDIUM("Medium", "Moderate privacy impact - data collection that could enable profiling"),
    LOW("Low", "Limited privacy implications - minimal personal data exposure"),
    MINIMAL("Minimal", "Negligible privacy concerns - standard infrastructure or consumer devices")
}

/**
 * Urgency level for recommendations.
 */
enum class RecommendationUrgency(val displayName: String, val color: String) {
    IMMEDIATE("Immediate Action", "#FF0000"),
    HIGH("High Priority", "#FF6600"),
    MEDIUM("Medium Priority", "#FFAA00"),
    LOW("Low Priority", "#00AA00"),
    INFORMATIONAL("For Awareness", "#0066FF")
}

// ==================== DATA CLASSES ====================

/**
 * Actionable recommendation for a detected device.
 */
data class Recommendation(
    val priority: Int, // 1-5, where 1 is highest priority
    val action: String,
    val urgency: RecommendationUrgency,
    val explanation: String? = null
)

/**
 * Threat score modifier based on contextual conditions.
 */
data class ThreatModifier(
    val condition: String,
    val scoreAdjustment: Int, // Positive or negative adjustment
    val description: String
)

/**
 * Comprehensive profile for a surveillance device type.
 */
data class DeviceTypeProfile(
    val deviceType: DeviceType,
    val description: String,
    val category: String,
    val surveillanceType: String,
    val typicalOperator: String,
    val legalFramework: String?,
    val dataCollected: List<String>,
    val privacyImpact: PrivacyImpact,
    val recommendations: List<Recommendation>,
    val aiPromptTemplate: String?,
    val threatScoreBase: Int,
    val threatModifiers: List<ThreatModifier>,
    // User-friendly fields for different explanation levels
    val simpleDescription: String? = null,
    val simplePrivacyImpact: String? = null
)

// ==================== REGISTRY ====================

/**
 * Registry containing profiles for all 76+ device types.
 * Provides centralized access to device information previously scattered
 * across DetectionAnalyzer.kt methods (getDeviceInfo, getDataCollectionTypes, getRecommendations).
 */
object DeviceTypeProfileRegistry {

    private val profiles: Map<DeviceType, DeviceTypeProfile> by lazy {
        buildProfileMap()
    }

    /**
     * Get the profile for a specific device type.
     * Returns a default profile if the device type is not found.
     */
    fun getProfile(deviceType: DeviceType): DeviceTypeProfile {
        return profiles[deviceType] ?: createDefaultProfile(deviceType)
    }

    /**
     * Get all registered profiles.
     */
    fun getAllProfiles(): Collection<DeviceTypeProfile> = profiles.values

    /**
     * Get profiles by category.
     */
    fun getProfilesByCategory(category: String): List<DeviceTypeProfile> {
        return profiles.values.filter { it.category.equals(category, ignoreCase = true) }
    }

    /**
     * Get profiles by privacy impact level.
     */
    fun getProfilesByPrivacyImpact(impact: PrivacyImpact): List<DeviceTypeProfile> {
        return profiles.values.filter { it.privacyImpact == impact }
    }

    /**
     * Get all unique categories.
     */
    fun getAllCategories(): Set<String> {
        return profiles.values.map { it.category }.toSet()
    }

    private fun buildProfileMap(): Map<DeviceType, DeviceTypeProfile> {
        return mapOf(
            // ==================== ALPR & Traffic Cameras ====================
            DeviceType.FLOCK_SAFETY_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
                description = "Flock Safety is an Automatic License Plate Recognition (ALPR) camera system. It captures images of all passing vehicles, extracting license plates, vehicle make/model/color, and timestamps. Data is stored in searchable databases accessible to law enforcement agencies and shared across jurisdictions.",
                category = "License Plate Reader",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Law enforcement, HOAs, businesses",
                legalFramework = "Varies by state; some states restrict ALPR data retention",
                dataCollected = listOf(
                    "License plate numbers and images",
                    "Vehicle make, model, and color",
                    "Timestamps and GPS coordinates",
                    "Direction of travel",
                    "Potentially visible occupant images",
                    "Historical travel pattern analysis"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Be aware that your vehicle is being recorded and tracked", RecommendationUrgency.HIGH),
                    Recommendation(2, "Consider varying your routes and travel times", RecommendationUrgency.MEDIUM),
                    Recommendation(3, "Note this location for future awareness", RecommendationUrgency.LOW),
                    Recommendation(4, "Check if your state has ALPR data retention limits", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = "Analyze this Flock Safety ALPR camera detection. Consider: data retention policies, cross-jurisdictional sharing, potential for pattern-of-life analysis from repeated captures.",
                threatScoreBase = 70,
                threatModifiers = listOf(
                    ThreatModifier("Near residence", 15, "ALPR near home enables precise movement tracking"),
                    ThreatModifier("Multiple cameras in cluster", 10, "Network of cameras increases tracking capability"),
                    ThreatModifier("High traffic area", -5, "Common infrastructure in busy areas")
                ),
                simpleDescription = "A camera that reads and records license plates of passing cars",
                simplePrivacyImpact = "Your car's movements can be tracked and stored in databases"
            ),

            DeviceType.LICENSE_PLATE_READER to DeviceTypeProfile(
                deviceType = DeviceType.LICENSE_PLATE_READER,
                description = "Generic license plate reader system that captures and stores vehicle plate data. May be stationary or mobile-mounted on police vehicles. Creates detailed records of vehicle movements over time.",
                category = "License Plate Reader",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Law enforcement, parking enforcement",
                legalFramework = "Subject to local ALPR regulations",
                dataCollected = listOf(
                    "License plate numbers and images",
                    "Vehicle make, model, and color",
                    "Timestamps and GPS coordinates",
                    "Direction of travel",
                    "Potentially visible occupant images",
                    "Historical travel pattern analysis"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Be aware that your vehicle is being recorded", RecommendationUrgency.HIGH),
                    Recommendation(2, "Consider varying your routes", RecommendationUrgency.MEDIUM),
                    Recommendation(3, "Note this location for future awareness", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 65,
                threatModifiers = listOf(
                    ThreatModifier("Mobile/police vehicle mounted", 10, "Active surveillance capability"),
                    ThreatModifier("Near residence", 15, "Enables precise movement tracking")
                )
            ),

            DeviceType.SPEED_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.SPEED_CAMERA,
                description = "Automated speed enforcement camera that captures vehicle speed and plate data. May issue automated citations. Stores vehicle images and speed records.",
                category = "Traffic Enforcement",
                surveillanceType = "Vehicle Monitoring",
                typicalOperator = "Municipal traffic enforcement",
                legalFramework = "Varies by jurisdiction; some states ban automated enforcement",
                dataCollected = listOf(
                    "Vehicle speed measurements",
                    "License plate numbers and images",
                    "Timestamps and location",
                    "Vehicle images for citation evidence"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Note this location for future awareness", RecommendationUrgency.LOW),
                    Recommendation(2, "Obey speed limits in this area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 40,
                threatModifiers = listOf(
                    ThreatModifier("Combined with ALPR", 15, "Also tracking vehicle movements"),
                    ThreatModifier("School zone", -10, "Expected safety infrastructure")
                )
            ),

            DeviceType.RED_LIGHT_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.RED_LIGHT_CAMERA,
                description = "Intersection camera that captures vehicles running red lights. Records vehicle images, plates, and violation evidence. May be combined with speed enforcement.",
                category = "Traffic Enforcement",
                surveillanceType = "Vehicle Monitoring",
                typicalOperator = "Municipal traffic enforcement",
                legalFramework = "Legal status varies by state and municipality",
                dataCollected = listOf(
                    "License plate numbers and images",
                    "Traffic violation evidence",
                    "Timestamps and location",
                    "Vehicle images"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Note this location for future awareness", RecommendationUrgency.LOW),
                    Recommendation(2, "Obey traffic signals in this area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 35,
                threatModifiers = listOf(
                    ThreatModifier("Combined with ALPR", 15, "Also tracking vehicle movements")
                )
            ),

            DeviceType.TOLL_READER to DeviceTypeProfile(
                deviceType = DeviceType.TOLL_READER,
                description = "Electronic toll collection reader (E-ZPass, SunPass, etc.). Tracks vehicle movements through toll points. Data may be subpoenaed for investigations.",
                category = "Toll System",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Toll authorities, DOT",
                legalFramework = "Toll data subject to subpoena; retention varies",
                dataCollected = listOf(
                    "Vehicle transponder ID",
                    "License plate (for non-transponder vehicles)",
                    "Timestamps of passage",
                    "Toll location",
                    "Account holder information"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware toll data creates travel records", RecommendationUrgency.LOW),
                    Recommendation(2, "Consider cash payment for anonymity (where available)", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 30,
                threatModifiers = listOf(
                    ThreatModifier("Near residence", 10, "Regular passage creates pattern")
                )
            ),

            DeviceType.TRAFFIC_SENSOR to DeviceTypeProfile(
                deviceType = DeviceType.TRAFFIC_SENSOR,
                description = "Traffic monitoring sensor for flow analysis. May use radar, cameras, or induction loops. Some systems capture individual vehicle data.",
                category = "Traffic Infrastructure",
                surveillanceType = "Traffic Analysis",
                typicalOperator = "DOT, municipal traffic management",
                legalFramework = "Generally considered public infrastructure",
                dataCollected = listOf(
                    "Vehicle counts and speeds",
                    "Traffic flow patterns",
                    "May capture individual vehicle data"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "No immediate action required", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 20,
                threatModifiers = emptyList()
            ),

            // ==================== Acoustic Surveillance ====================
            DeviceType.RAVEN_GUNSHOT_DETECTOR to DeviceTypeProfile(
                deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
                description = "Raven is an acoustic gunshot detection system using networked microphones to detect and triangulate gunfire. While designed for public safety, it continuously monitors ambient audio in the area and may capture conversations.",
                category = "Acoustic Surveillance",
                surveillanceType = "Audio Monitoring",
                typicalOperator = "Law enforcement",
                legalFramework = "Generally considered public space monitoring",
                dataCollected = listOf(
                    "Continuous ambient audio monitoring",
                    "Acoustic signatures and sound patterns",
                    "Precise location via triangulation",
                    "Audio snippets around detected events",
                    "Timestamps of all acoustic events"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Be aware that audio may be captured in this area", RecommendationUrgency.HIGH),
                    Recommendation(2, "Avoid sensitive conversations in public spaces near sensors", RecommendationUrgency.HIGH),
                    Recommendation(3, "Document this detection with timestamp and location", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = "Analyze this Raven gunshot detector. Consider: continuous audio monitoring capabilities, potential for conversation capture, triangulation accuracy, and data retention policies.",
                threatScoreBase = 85,
                threatModifiers = listOf(
                    ThreatModifier("Multiple sensors nearby", 10, "Triangulation capability active"),
                    ThreatModifier("Near residence", 15, "Persistent audio monitoring of private area")
                ),
                simpleDescription = "A microphone system that listens for gunshots but also captures other sounds",
                simplePrivacyImpact = "Conversations and sounds in the area may be recorded"
            ),

            DeviceType.SHOTSPOTTER to DeviceTypeProfile(
                deviceType = DeviceType.SHOTSPOTTER,
                description = "ShotSpotter is a citywide acoustic surveillance network. Uses arrays of sensitive microphones that continuously record and analyze ambient audio for gunshot-like sounds. Audio snippets are reviewed by analysts.",
                category = "Acoustic Surveillance",
                surveillanceType = "Continuous Audio Monitoring",
                typicalOperator = "Law enforcement (contracted service)",
                legalFramework = "Has faced legal challenges over audio retention",
                dataCollected = listOf(
                    "Continuous ambient audio monitoring",
                    "Acoustic signatures and sound patterns",
                    "Precise location via triangulation",
                    "Audio snippets around detected events",
                    "Timestamps of all acoustic events"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Be aware that audio is continuously monitored", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Avoid sensitive conversations in coverage areas", RecommendationUrgency.HIGH),
                    Recommendation(3, "Research ShotSpotter coverage in your city", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = "Analyze this ShotSpotter detection. Consider: citywide audio surveillance implications, human analyst review of audio clips, legal challenges to the technology, and accuracy controversies.",
                threatScoreBase = 90,
                threatModifiers = listOf(
                    ThreatModifier("Dense urban area", 5, "Extensive sensor coverage"),
                    ThreatModifier("Near residence", 15, "Persistent monitoring of home area")
                ),
                simpleDescription = "Citywide microphone network that listens to everything to detect gunshots",
                simplePrivacyImpact = "All sounds including conversations may be recorded and reviewed by analysts"
            ),

            // ==================== Cell Site Simulators ====================
            DeviceType.STINGRAY_IMSI to DeviceTypeProfile(
                deviceType = DeviceType.STINGRAY_IMSI,
                description = "Cell-site simulator (IMSI catcher/Stingray) that mimics a cell tower to force phones to connect. Detection analysis includes: encryption downgrade chain tracking (5G-4G-3G-2G), signal spike correlation with new tower appearances, IMSI catcher signature scoring (0-100%), movement analysis via Haversine distance calculations, and cell trust scoring based on historical tower observations. Can intercept calls, texts, and precisely track device locations.",
                category = "Cell Site Simulator",
                surveillanceType = "Communications Interception",
                typicalOperator = "Law enforcement (requires warrant in most jurisdictions)",
                legalFramework = "Carpenter v. US requires warrant for historical location data",
                dataCollected = listOf(
                    "IMSI (unique phone identifier)",
                    "IMEI (device hardware ID)",
                    "Phone calls (content and metadata)",
                    "SMS/text messages",
                    "Real-time precise location via triangulation",
                    "Device model and capabilities",
                    "All nearby device identifiers within range",
                    "Encryption capability downgrades forced on devices",
                    "Movement patterns via cell handoff analysis",
                    "Network attachment timestamps and duration"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Switch to airplane mode if you need complete privacy", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Signal-based encryption apps (Signal, WhatsApp) still provide some protection", RecommendationUrgency.IMMEDIATE),
                    Recommendation(3, "Check technical details for IMSI catcher score and encryption downgrade evidence", RecommendationUrgency.HIGH),
                    Recommendation(4, "If movement analysis shows 'impossible speed', your location may be manipulated", RecommendationUrgency.HIGH),
                    Recommendation(5, "Consider using WiFi calling if available and trusted network exists", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = "Analyze this cell-site simulator detection. Consider: encryption downgrade severity, IMSI catcher signature confidence, movement anomalies, affected device count, and potential legal implications under Carpenter v. US.",
                threatScoreBase = 95,
                threatModifiers = listOf(
                    ThreatModifier("Encryption downgrade detected", 5, "Active interception likely"),
                    ThreatModifier("Signal spike correlated", 5, "Strong IMSI catcher indicator"),
                    ThreatModifier("Multiple cells affected", 10, "Wide-area surveillance"),
                    ThreatModifier("Near residence", 15, "Targeted surveillance possible")
                ),
                simpleDescription = "A fake cell tower that forces your phone to connect to it",
                simplePrivacyImpact = "Your calls, texts, and location can be intercepted"
            ),

            // ==================== Forensic Equipment ====================
            DeviceType.CELLEBRITE_FORENSICS to DeviceTypeProfile(
                deviceType = DeviceType.CELLEBRITE_FORENSICS,
                description = "Cellebrite is mobile forensics equipment used to extract data from phones including deleted content, encrypted data, and app data. Detection suggests active forensic operations nearby.",
                category = "Mobile Forensics",
                surveillanceType = "Device Data Extraction",
                typicalOperator = "Law enforcement, private investigators",
                legalFramework = "Generally requires warrant for search",
                dataCollected = listOf(
                    "All phone contents including deleted data",
                    "Messages from all apps",
                    "Photos and videos",
                    "Location history",
                    "Contacts and call logs",
                    "App data and credentials",
                    "Encrypted content (when bypassed)"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Be aware of active forensic operations in the area", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Ensure your device has strong encryption enabled", RecommendationUrgency.HIGH),
                    Recommendation(3, "Consider your legal rights regarding device searches", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = "Analyze this Cellebrite forensics device detection. Consider: potential ongoing investigation, warrant requirements, data extraction capabilities, and user's legal rights.",
                threatScoreBase = 90,
                threatModifiers = listOf(
                    ThreatModifier("Law enforcement area", 10, "Active investigation likely"),
                    ThreatModifier("Near courthouse/police station", -20, "Expected equipment location")
                )
            ),

            DeviceType.GRAYKEY_DEVICE to DeviceTypeProfile(
                deviceType = DeviceType.GRAYKEY_DEVICE,
                description = "GrayKey is an iPhone unlocking and forensics device. Can bypass iOS security to extract device contents. Indicates active mobile forensics operation.",
                category = "Mobile Forensics",
                surveillanceType = "Device Data Extraction",
                typicalOperator = "Law enforcement",
                legalFramework = "Requires warrant; controversial legality of bypass techniques",
                dataCollected = listOf(
                    "All phone contents including deleted data",
                    "Messages from all apps",
                    "Photos and videos",
                    "Location history",
                    "Contacts and call logs",
                    "App data and credentials",
                    "Encrypted content (when bypassed)"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Be aware of active forensic operations", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Keep your iOS device updated for latest security", RecommendationUrgency.HIGH),
                    Recommendation(3, "Use a strong alphanumeric passcode", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 90,
                threatModifiers = listOf(
                    ThreatModifier("Law enforcement area", 10, "Active investigation likely")
                )
            ),

            // ==================== Smart Home Cameras ====================
            DeviceType.RING_DOORBELL to DeviceTypeProfile(
                deviceType = DeviceType.RING_DOORBELL,
                description = "Amazon Ring doorbell/camera. Records video and audio of public areas. Footage may be shared with law enforcement through Ring's Neighbors program or via subpoena without owner notification.",
                category = "Smart Home Camera",
                surveillanceType = "Video/Audio Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Amazon partners with 2,000+ police departments",
                dataCollected = listOf(
                    "Video footage (24/7 or motion-triggered)",
                    "Audio recordings",
                    "Motion detection events with timestamps",
                    "Person/package detection (AI-enabled)",
                    "Facial recognition data (some models)",
                    "Visitor patterns and frequency"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware you may be recorded when near this property", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Ring footage can be shared with police without owner knowledge", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = "Analyze this Ring doorbell detection. Consider: Amazon's law enforcement partnerships, Neighbors app surveillance network, audio recording capabilities, and facial recognition features.",
                threatScoreBase = 45,
                threatModifiers = listOf(
                    ThreatModifier("Multiple Ring devices nearby", 10, "Surveillance network"),
                    ThreatModifier("Residential area", -5, "Common consumer device")
                ),
                simpleDescription = "A smart doorbell camera that records video and audio",
                simplePrivacyImpact = "Your image may be recorded and potentially shared with police"
            ),

            DeviceType.NEST_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.NEST_CAMERA,
                description = "Google Nest camera/doorbell. Provides 24/7 video recording with cloud storage. Google may comply with law enforcement requests for footage. Features AI-powered person detection.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Subject to Google's law enforcement request policies",
                dataCollected = listOf(
                    "Video footage (24/7 or motion-triggered)",
                    "Audio recordings",
                    "Motion detection events with timestamps",
                    "Person/package detection (AI-enabled)",
                    "Facial recognition data (some models)",
                    "Visitor patterns and frequency"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware you may be recorded when near this property", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Footage is stored in Google's cloud", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 40,
                threatModifiers = listOf(
                    ThreatModifier("Multiple cameras nearby", 10, "Surveillance coverage")
                )
            ),

            DeviceType.ARLO_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.ARLO_CAMERA,
                description = "Arlo security camera with cloud storage. May record continuously or on motion detection. Footage accessible to law enforcement via subpoena.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Subject to subpoena",
                dataCollected = listOf(
                    "Video footage (24/7 or motion-triggered)",
                    "Audio recordings",
                    "Motion detection events with timestamps",
                    "Person/package detection (AI-enabled)",
                    "Facial recognition data (some models)",
                    "Visitor patterns and frequency"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware you may be recorded when near this property", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 40,
                threatModifiers = emptyList()
            ),

            DeviceType.WYZE_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.WYZE_CAMERA,
                description = "Wyze smart camera. Low-cost camera with cloud connectivity. Has had security vulnerabilities in the past. May share data with third parties.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Subject to subpoena; has had data breaches",
                dataCollected = listOf(
                    "Video footage (24/7 or motion-triggered)",
                    "Audio recordings",
                    "Motion detection events with timestamps",
                    "Person/package detection (AI-enabled)",
                    "Facial recognition data (some models)",
                    "Visitor patterns and frequency"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware you may be recorded", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Wyze has had security vulnerabilities", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 45,
                threatModifiers = listOf(
                    ThreatModifier("Known security vulnerabilities", 5, "Data breach risk")
                )
            ),

            DeviceType.EUFY_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.EUFY_CAMERA,
                description = "Eufy security camera. Marketed as local-only storage but has sent data to cloud. Be aware of potential data collection beyond stated privacy policy.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Has sent data to cloud despite local-only claims",
                dataCollected = listOf(
                    "Video footage (24/7 or motion-triggered)",
                    "Audio recordings",
                    "Motion detection events with timestamps",
                    "Person/package detection (AI-enabled)",
                    "Facial recognition data (some models)",
                    "Visitor patterns and frequency"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware you may be recorded", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Eufy has sent data to cloud despite privacy claims", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 50,
                threatModifiers = listOf(
                    ThreatModifier("Privacy policy violations", 10, "Data collection beyond stated policy")
                )
            ),

            DeviceType.BLINK_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.BLINK_CAMERA,
                description = "Amazon Blink camera. Part of Amazon's home security ecosystem. May participate in Sidewalk mesh network and share footage with law enforcement.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Subject to Amazon's law enforcement policies",
                dataCollected = listOf(
                    "Video footage (24/7 or motion-triggered)",
                    "Audio recordings",
                    "Motion detection events with timestamps",
                    "Person/package detection (AI-enabled)",
                    "Facial recognition data (some models)",
                    "Visitor patterns and frequency"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware you may be recorded", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Part of Amazon's Sidewalk network", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 45,
                threatModifiers = listOf(
                    ThreatModifier("Sidewalk network enabled", 5, "Mesh tracking capability")
                )
            ),

            // ==================== Security Systems ====================
            DeviceType.SIMPLISAFE_DEVICE to DeviceTypeProfile(
                deviceType = DeviceType.SIMPLISAFE_DEVICE,
                description = "SimpliSafe security system component. Professional monitoring service may share data with authorities. Includes cameras, sensors, and alarm systems.",
                category = "Security System",
                surveillanceType = "Home Monitoring",
                typicalOperator = "Private homeowners",
                legalFramework = "Professional monitoring with law enforcement cooperation",
                dataCollected = listOf(
                    "Video footage (if camera equipped)",
                    "Motion and entry sensor events",
                    "Alarm history",
                    "System status logs"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Standard home security system", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 25,
                threatModifiers = emptyList()
            ),

            DeviceType.ADT_DEVICE to DeviceTypeProfile(
                deviceType = DeviceType.ADT_DEVICE,
                description = "ADT security system component. One of the largest security providers. Professional monitoring with law enforcement partnerships.",
                category = "Security System",
                surveillanceType = "Home Monitoring",
                typicalOperator = "Private homeowners",
                legalFramework = "Professional monitoring with law enforcement cooperation",
                dataCollected = listOf(
                    "Video footage (if camera equipped)",
                    "Motion and entry sensor events",
                    "Alarm history",
                    "System status logs"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Standard home security system", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 25,
                threatModifiers = emptyList()
            ),

            DeviceType.VIVINT_DEVICE to DeviceTypeProfile(
                deviceType = DeviceType.VIVINT_DEVICE,
                description = "Vivint smart home security device. Full home automation and security monitoring with cloud connectivity and professional monitoring.",
                category = "Security System",
                surveillanceType = "Home Monitoring",
                typicalOperator = "Private homeowners",
                legalFramework = "Professional monitoring with potential law enforcement access",
                dataCollected = listOf(
                    "Video footage (if camera equipped)",
                    "Motion and entry sensor events",
                    "Alarm history",
                    "Smart home activity logs"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Standard home security system", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 25,
                threatModifiers = emptyList()
            ),

            // ==================== Personal Trackers ====================
            DeviceType.AIRTAG to DeviceTypeProfile(
                deviceType = DeviceType.AIRTAG,
                description = "Apple AirTag Bluetooth tracker. Uses Apple's Find My network (billions of devices) for location tracking. If you don't own this and see it repeatedly, it may be tracking you.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking",
                typicalOperator = "Private individuals",
                legalFramework = "Apple added anti-stalking alerts; illegal to track without consent",
                dataCollected = listOf(
                    "Real-time location via network",
                    "Location history and movement patterns",
                    "Timestamps of all location updates",
                    "Proximity to tracker owner's devices"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Check your belongings, vehicle, and clothing for hidden trackers", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "If tracker persists, contact local authorities", RecommendationUrgency.HIGH),
                    Recommendation(3, "Use Apple's Tracker Detect app if on Android", RecommendationUrgency.MEDIUM),
                    Recommendation(4, "Document repeated detections with timestamps", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = "Analyze this AirTag detection. Consider: stalking risk if not owned by user, Apple's anti-stalking measures, Find My network reach, and detection patterns suggesting following behavior.",
                threatScoreBase = 60,
                threatModifiers = listOf(
                    ThreatModifier("Detected multiple times", 20, "Possible stalking"),
                    ThreatModifier("At multiple locations", 25, "Following pattern detected"),
                    ThreatModifier("Owner identified as known", -40, "Likely personal item")
                ),
                simpleDescription = "A small tracking device that can show your location to someone else",
                simplePrivacyImpact = "If this isn't yours, someone may be tracking your location"
            ),

            DeviceType.TILE_TRACKER to DeviceTypeProfile(
                deviceType = DeviceType.TILE_TRACKER,
                description = "Tile Bluetooth tracker. Uses Tile's network for location tracking. Check your belongings if you see this repeatedly and don't own a Tile.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking",
                typicalOperator = "Private individuals",
                legalFramework = "Illegal to track without consent",
                dataCollected = listOf(
                    "Real-time location via network",
                    "Location history and movement patterns",
                    "Timestamps of all location updates",
                    "Proximity to tracker owner's devices"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Check your belongings, vehicle, and clothing for hidden trackers", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "If tracker persists, contact local authorities", RecommendationUrgency.HIGH),
                    Recommendation(3, "Download Tile app to identify unknown trackers", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 55,
                threatModifiers = listOf(
                    ThreatModifier("Detected multiple times", 20, "Possible stalking"),
                    ThreatModifier("At multiple locations", 25, "Following pattern detected")
                ),
                simpleDescription = "A tracking device that can locate your position",
                simplePrivacyImpact = "If this isn't yours, someone may be tracking you"
            ),

            DeviceType.SAMSUNG_SMARTTAG to DeviceTypeProfile(
                deviceType = DeviceType.SAMSUNG_SMARTTAG,
                description = "Samsung SmartTag tracker. Uses Samsung's Galaxy Find Network. Can track items or potentially be used for unwanted tracking.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking",
                typicalOperator = "Private individuals",
                legalFramework = "Illegal to track without consent",
                dataCollected = listOf(
                    "Real-time location via network",
                    "Location history and movement patterns",
                    "Timestamps of all location updates",
                    "Proximity to tracker owner's devices"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Check your belongings, vehicle, and clothing for hidden trackers", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "If tracker persists, contact local authorities", RecommendationUrgency.HIGH),
                    Recommendation(3, "Use Samsung's SmartThings app to scan for unknown tags", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 55,
                threatModifiers = listOf(
                    ThreatModifier("Detected multiple times", 20, "Possible stalking"),
                    ThreatModifier("At multiple locations", 25, "Following pattern detected")
                ),
                simpleDescription = "A tracking device using Samsung's network",
                simplePrivacyImpact = "If this isn't yours, someone may be tracking you"
            ),

            DeviceType.GENERIC_BLE_TRACKER to DeviceTypeProfile(
                deviceType = DeviceType.GENERIC_BLE_TRACKER,
                description = "Generic Bluetooth Low Energy tracker detected. Could be a legitimate item tracker or potentially used for unwanted surveillance.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking",
                typicalOperator = "Unknown",
                legalFramework = "Illegal to track without consent",
                dataCollected = listOf(
                    "Real-time location via network",
                    "Location history and movement patterns",
                    "Timestamps of all location updates"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Check your belongings for hidden trackers", RecommendationUrgency.HIGH),
                    Recommendation(2, "If tracker persists, contact local authorities", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 50,
                threatModifiers = listOf(
                    ThreatModifier("Detected multiple times", 20, "Possible stalking"),
                    ThreatModifier("At multiple locations", 25, "Following pattern detected")
                )
            ),

            // ==================== Mesh Networks ====================
            DeviceType.AMAZON_SIDEWALK to DeviceTypeProfile(
                deviceType = DeviceType.AMAZON_SIDEWALK,
                description = "Amazon Sidewalk is a shared mesh network using Ring and Echo devices. Can track Sidewalk-enabled devices across the network and raises privacy concerns about shared bandwidth.",
                category = "Mesh Network",
                surveillanceType = "Network Tracking",
                typicalOperator = "Amazon (opt-out required)",
                legalFramework = "Opt-out required; enabled by default on Amazon devices",
                dataCollected = listOf(
                    "Device presence and proximity",
                    "Location tracking of Sidewalk devices",
                    "Network traffic metadata",
                    "Tile tracker locations"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware of mesh network tracking capability", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Disable Sidewalk on your Amazon devices if concerned", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 40,
                threatModifiers = listOf(
                    ThreatModifier("Multiple Sidewalk devices nearby", 10, "Dense tracking mesh")
                )
            ),

            // ==================== Network Attack Devices ====================
            DeviceType.WIFI_PINEAPPLE to DeviceTypeProfile(
                deviceType = DeviceType.WIFI_PINEAPPLE,
                description = "WiFi Pineapple is a penetration testing device capable of man-in-the-middle attacks, credential capture, and network manipulation. Detection suggests active security testing or potential attack.",
                category = "Network Attack Tool",
                surveillanceType = "Network Interception",
                typicalOperator = "Security researchers, malicious actors",
                legalFramework = "Illegal to use without authorization",
                dataCollected = listOf(
                    "Network credentials (if captured)",
                    "Unencrypted network traffic",
                    "Website visits and DNS queries",
                    "Device identifiers (MAC addresses)",
                    "Potentially sensitive data in transit"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Do NOT connect to unknown WiFi networks", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Use cellular data instead of WiFi in this area", RecommendationUrgency.IMMEDIATE),
                    Recommendation(3, "Verify network names before connecting", RecommendationUrgency.HIGH),
                    Recommendation(4, "Use VPN if you must connect to WiFi", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = "Analyze this WiFi Pineapple detection. Consider: active attack vs security testing, nearby sensitive locations, credential capture risk, and network traffic interception capabilities.",
                threatScoreBase = 85,
                threatModifiers = listOf(
                    ThreatModifier("Near financial institution", 15, "High-value target area"),
                    ThreatModifier("Security conference nearby", -30, "Likely authorized testing")
                ),
                simpleDescription = "A hacking device that can steal your WiFi passwords and data",
                simplePrivacyImpact = "Your internet traffic and passwords could be captured"
            ),

            DeviceType.ROGUE_AP to DeviceTypeProfile(
                deviceType = DeviceType.ROGUE_AP,
                description = "Unauthorized or suspicious access point detected. May be attempting evil twin attacks or network interception. Do not connect to unknown networks.",
                category = "Rogue Network",
                surveillanceType = "Network Interception",
                typicalOperator = "Malicious actors, security testers",
                legalFramework = "Illegal without authorization",
                dataCollected = listOf(
                    "Network credentials (if captured)",
                    "Unencrypted network traffic",
                    "Website visits and DNS queries",
                    "Device identifiers (MAC addresses)",
                    "Potentially sensitive data in transit"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Do NOT connect to unknown WiFi networks", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Use cellular data instead of WiFi", RecommendationUrgency.HIGH),
                    Recommendation(3, "Verify network names before connecting", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 75,
                threatModifiers = listOf(
                    ThreatModifier("Mimics known network", 15, "Evil twin attack likely")
                )
            ),

            DeviceType.MAN_IN_MIDDLE to DeviceTypeProfile(
                deviceType = DeviceType.MAN_IN_MIDDLE,
                description = "Potential man-in-the-middle attack device detected. May be intercepting network traffic. Use VPN and verify HTTPS connections.",
                category = "Network Attack",
                surveillanceType = "Traffic Interception",
                typicalOperator = "Malicious actors, security testers",
                legalFramework = "Illegal without authorization",
                dataCollected = listOf(
                    "Network credentials (if captured)",
                    "Unencrypted network traffic",
                    "Website visits and DNS queries",
                    "Device identifiers (MAC addresses)",
                    "Potentially sensitive data in transit"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Disconnect from current network immediately", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Use cellular data or trusted network", RecommendationUrgency.IMMEDIATE),
                    Recommendation(3, "Verify HTTPS on all sensitive sites", RecommendationUrgency.HIGH),
                    Recommendation(4, "Use VPN for all connections", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 90,
                threatModifiers = listOf(
                    ThreatModifier("Active SSL stripping detected", 10, "Encryption bypass attack")
                )
            ),

            DeviceType.PACKET_SNIFFER to DeviceTypeProfile(
                deviceType = DeviceType.PACKET_SNIFFER,
                description = "Network packet capture device detected. May be monitoring network traffic for reconnaissance or data exfiltration.",
                category = "Network Monitoring",
                surveillanceType = "Traffic Analysis",
                typicalOperator = "Security researchers, IT administrators, malicious actors",
                legalFramework = "Legal for authorized network monitoring only",
                dataCollected = listOf(
                    "All network packet data",
                    "Unencrypted communications",
                    "Device identifiers",
                    "Traffic patterns"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Use encrypted connections (HTTPS, VPN)", RecommendationUrgency.HIGH),
                    Recommendation(2, "Avoid transmitting sensitive data on this network", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 70,
                threatModifiers = listOf(
                    ThreatModifier("Corporate network", -20, "Likely authorized monitoring")
                )
            ),

            // ==================== Drones ====================
            DeviceType.DRONE to DeviceTypeProfile(
                deviceType = DeviceType.DRONE,
                description = "Aerial drone/UAV detected via WiFi signal. Could be recreational, commercial, or surveillance-related. Drones can carry cameras, thermal sensors, and other surveillance equipment.",
                category = "Aerial Surveillance",
                surveillanceType = "Aerial Monitoring",
                typicalOperator = "Hobbyists, commercial operators, law enforcement",
                legalFramework = "FAA regulations; privacy laws vary by state",
                dataCollected = listOf(
                    "Aerial video and photography",
                    "Thermal/infrared imagery (equipped)",
                    "Real-time streaming capability",
                    "GPS coordinates of targets",
                    "License plate capture (equipped)"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware of potential aerial surveillance", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Note the drone's behavior pattern", RecommendationUrgency.LOW),
                    Recommendation(3, "Report suspicious drone activity to authorities if needed", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 50,
                threatModifiers = listOf(
                    ThreatModifier("Hovering over property", 20, "Possible targeted surveillance"),
                    ThreatModifier("Near private residence", 15, "Privacy concern"),
                    ThreatModifier("Public event area", -10, "Likely authorized coverage")
                ),
                simpleDescription = "A flying drone that may have cameras",
                simplePrivacyImpact = "You may be photographed or recorded from above"
            ),

            // ==================== Commercial Surveillance ====================
            DeviceType.CCTV_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.CCTV_CAMERA,
                description = "Closed-circuit television camera. May be part of business or municipal surveillance system. Footage typically retained for days to months.",
                category = "Video Surveillance",
                surveillanceType = "Video Recording",
                typicalOperator = "Businesses, municipalities",
                legalFramework = "Generally legal in public spaces; varies for audio",
                dataCollected = listOf(
                    "Video footage",
                    "Timestamps",
                    "Potentially audio (some models)"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Be aware you are being recorded in this area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 30,
                threatModifiers = listOf(
                    ThreatModifier("High density cluster", 10, "Intensive coverage area")
                )
            ),

            DeviceType.PTZ_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.PTZ_CAMERA,
                description = "Pan-tilt-zoom camera with remote control capabilities. Can actively track subjects and provide detailed surveillance coverage.",
                category = "Video Surveillance",
                surveillanceType = "Active Video Tracking",
                typicalOperator = "Security operations, law enforcement",
                legalFramework = "Legal in public spaces",
                dataCollected = listOf(
                    "Video footage with zoom capability",
                    "Subject tracking data",
                    "Timestamps"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware of active tracking capability", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Camera can zoom in on individuals", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 45,
                threatModifiers = listOf(
                    ThreatModifier("Actively tracking", 20, "Targeted surveillance")
                )
            ),

            DeviceType.THERMAL_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.THERMAL_CAMERA,
                description = "Thermal/infrared camera that can see heat signatures through walls, detect people in darkness, and identify concealed individuals.",
                category = "Thermal Surveillance",
                surveillanceType = "Thermal Imaging",
                typicalOperator = "Law enforcement, security, military",
                legalFramework = "Kyllo v. US restricts warrantless thermal imaging of homes",
                dataCollected = listOf(
                    "Heat signature images",
                    "Body detection through obstacles",
                    "Movement patterns in darkness"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Be aware thermal can see through some barriers", RecommendationUrgency.HIGH),
                    Recommendation(2, "Thermal imaging of homes requires warrant (Kyllo)", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 70,
                threatModifiers = listOf(
                    ThreatModifier("Aimed at residence", 20, "Potential 4th Amendment issue")
                )
            ),

            DeviceType.NIGHT_VISION to DeviceTypeProfile(
                deviceType = DeviceType.NIGHT_VISION,
                description = "Night vision device capable of surveillance in low-light conditions. May be handheld or camera-mounted.",
                category = "Night Surveillance",
                surveillanceType = "Low-Light Monitoring",
                typicalOperator = "Security, law enforcement, military",
                legalFramework = "Legal for purchase; use restrictions vary",
                dataCollected = listOf(
                    "Low-light video/images",
                    "Surveillance in darkness"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware surveillance is possible even in darkness", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 55,
                threatModifiers = emptyList()
            ),

            DeviceType.HIDDEN_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.HIDDEN_CAMERA,
                description = "Covert camera detected. May be hidden in everyday objects. Check for recording devices in private spaces.",
                category = "Covert Surveillance",
                surveillanceType = "Hidden Video Recording",
                typicalOperator = "Unknown - potentially illegal installation",
                legalFramework = "Generally illegal in private spaces without consent",
                dataCollected = listOf(
                    "Covert video recording",
                    "Potentially audio",
                    "Private activities"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Search the area for hidden cameras", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Check common hiding spots (smoke detectors, clocks, outlets)", RecommendationUrgency.IMMEDIATE),
                    Recommendation(3, "Contact authorities if found in rental/hotel", RecommendationUrgency.HIGH),
                    Recommendation(4, "Use RF detector to locate hidden devices", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = "Analyze this hidden camera detection. Consider: location context (hotel, rental, workplace), legality of placement, search recommendations, and reporting options.",
                threatScoreBase = 90,
                threatModifiers = listOf(
                    ThreatModifier("Private space (bedroom/bathroom)", 10, "Serious privacy violation"),
                    ThreatModifier("Rental/hotel room", 10, "Common illegal placement")
                ),
                simpleDescription = "A hidden camera recording without your knowledge",
                simplePrivacyImpact = "Your private activities may be recorded secretly"
            ),

            // ==================== Retail & Commercial Tracking ====================
            DeviceType.BLUETOOTH_BEACON to DeviceTypeProfile(
                deviceType = DeviceType.BLUETOOTH_BEACON,
                description = "Bluetooth beacon for indoor positioning and tracking. Used in retail stores to track customer movements and send targeted advertisements.",
                category = "Retail Tracking",
                surveillanceType = "Indoor Location Tracking",
                typicalOperator = "Retailers, venues, advertisers",
                legalFramework = "Generally legal with privacy policy disclosure",
                dataCollected = listOf(
                    "Device presence and proximity",
                    "Dwell time at locations",
                    "Movement patterns within space",
                    "Return visit frequency",
                    "Device identifiers"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Disable Bluetooth when not needed to avoid tracking", RecommendationUrgency.LOW),
                    Recommendation(2, "Be aware of indoor tracking in retail spaces", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 25,
                threatModifiers = emptyList()
            ),

            DeviceType.RETAIL_TRACKER to DeviceTypeProfile(
                deviceType = DeviceType.RETAIL_TRACKER,
                description = "Retail tracking device for customer analytics. Monitors shopping patterns, dwell time, and movement through stores.",
                category = "Retail Analytics",
                surveillanceType = "Customer Tracking",
                typicalOperator = "Retailers",
                legalFramework = "Legal with disclosure",
                dataCollected = listOf(
                    "Device presence and proximity",
                    "Dwell time at locations",
                    "Movement patterns within space",
                    "Return visit frequency",
                    "Device identifiers"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Disable WiFi/Bluetooth when not needed", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 25,
                threatModifiers = emptyList()
            ),

            DeviceType.CROWD_ANALYTICS to DeviceTypeProfile(
                deviceType = DeviceType.CROWD_ANALYTICS,
                description = "Crowd analytics sensor for counting and tracking people. May use WiFi probe requests, cameras, or other sensors to monitor crowds.",
                category = "People Counting",
                surveillanceType = "Crowd Monitoring",
                typicalOperator = "Venues, retailers, municipalities",
                legalFramework = "Generally legal for aggregate data",
                dataCollected = listOf(
                    "People counts",
                    "Movement flow patterns",
                    "Density mapping",
                    "Device identifiers (WiFi-based)"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Disable WiFi to reduce tracking via probe requests", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 20,
                threatModifiers = emptyList()
            ),

            // ==================== Facial Recognition ====================
            DeviceType.FACIAL_RECOGNITION to DeviceTypeProfile(
                deviceType = DeviceType.FACIAL_RECOGNITION,
                description = "Facial recognition system detected. Captures and analyzes faces for identification. May be connected to law enforcement databases.",
                category = "Biometric Surveillance",
                surveillanceType = "Facial Recognition",
                typicalOperator = "Law enforcement, businesses, venues",
                legalFramework = "Banned in some cities; BIPA in Illinois",
                dataCollected = listOf(
                    "Facial biometric data",
                    "Identity matches against databases",
                    "Timestamps and locations of sightings",
                    "Associated profile information",
                    "Movement patterns across cameras"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Be aware your face may be scanned and identified", RecommendationUrgency.HIGH),
                    Recommendation(2, "Consider wearing face covering if legal and appropriate", RecommendationUrgency.MEDIUM),
                    Recommendation(3, "Check if facial recognition is banned in your area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = "Analyze this facial recognition detection. Consider: database connections, legal status in this jurisdiction, biometric data retention, and identification accuracy concerns.",
                threatScoreBase = 80,
                threatModifiers = listOf(
                    ThreatModifier("Connected to law enforcement DB", 15, "Identity matching active"),
                    ThreatModifier("Banned jurisdiction", -30, "May be illegal installation")
                ),
                simpleDescription = "A camera that can identify who you are by your face",
                simplePrivacyImpact = "Your identity can be tracked across locations"
            ),

            DeviceType.CLEARVIEW_AI to DeviceTypeProfile(
                deviceType = DeviceType.CLEARVIEW_AI,
                description = "Clearview AI facial recognition system. Uses scraped social media photos to identify individuals. Highly controversial with 30+ billion face database.",
                category = "Biometric Surveillance",
                surveillanceType = "Facial Recognition",
                typicalOperator = "Law enforcement",
                legalFramework = "Banned in several countries; multiple lawsuits pending",
                dataCollected = listOf(
                    "Facial biometric data",
                    "Identity matches against databases",
                    "Timestamps and locations of sightings",
                    "Associated profile information",
                    "Movement patterns across cameras",
                    "Social media profile links"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Your social media photos may be in their database", RecommendationUrgency.HIGH),
                    Recommendation(2, "Consider adjusting social media privacy settings", RecommendationUrgency.MEDIUM),
                    Recommendation(3, "Research Clearview AI opt-out options", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = "Analyze this Clearview AI detection. Consider: 30+ billion face database, social media scraping controversy, legal challenges, and user opt-out options.",
                threatScoreBase = 90,
                threatModifiers = listOf(
                    ThreatModifier("Active law enforcement use", 10, "Active identification")
                ),
                simpleDescription = "Advanced facial recognition using billions of social media photos",
                simplePrivacyImpact = "You can be identified from social media photos you posted"
            ),

            // ==================== Law Enforcement Specific ====================
            DeviceType.BODY_CAMERA to DeviceTypeProfile(
                deviceType = DeviceType.BODY_CAMERA,
                description = "Police body-worn camera detected. Records video and audio of interactions. Footage may be subject to FOIA requests.",
                category = "Body Camera",
                surveillanceType = "Video/Audio Recording",
                typicalOperator = "Law enforcement",
                legalFramework = "Subject to department policy and FOIA",
                dataCollected = listOf(
                    "Video of interactions",
                    "Audio recordings",
                    "Timestamps and location",
                    "Officer identification"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Be aware your interaction is being recorded", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "You may be able to request footage via FOIA", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 35,
                threatModifiers = listOf(
                    ThreatModifier("During traffic stop", 10, "Direct interaction recorded")
                )
            ),

            DeviceType.POLICE_RADIO to DeviceTypeProfile(
                deviceType = DeviceType.POLICE_RADIO,
                description = "Police radio system detected. Indicates law enforcement presence in the area.",
                category = "Communications",
                surveillanceType = "Radio Communications",
                typicalOperator = "Law enforcement",
                legalFramework = "Standard law enforcement equipment",
                dataCollected = listOf(
                    "Indicates law enforcement presence",
                    "Radio communications (encrypted)"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Law enforcement is operating in this area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 25,
                threatModifiers = emptyList()
            ),

            DeviceType.POLICE_VEHICLE to DeviceTypeProfile(
                deviceType = DeviceType.POLICE_VEHICLE,
                description = "Police or emergency vehicle wireless system detected. May include ALPR, mobile data terminals, and radio equipment.",
                category = "Mobile Surveillance",
                surveillanceType = "Vehicle-based Monitoring",
                typicalOperator = "Law enforcement",
                legalFramework = "Standard law enforcement equipment",
                dataCollected = listOf(
                    "ALPR data (if equipped)",
                    "Radio communications",
                    "Mobile data terminal activity"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Law enforcement vehicle nearby", RecommendationUrgency.INFORMATIONAL),
                    Recommendation(2, "May have ALPR scanning capability", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 40,
                threatModifiers = listOf(
                    ThreatModifier("ALPR equipped", 15, "Active vehicle scanning")
                )
            ),

            DeviceType.MOTOROLA_POLICE_TECH to DeviceTypeProfile(
                deviceType = DeviceType.MOTOROLA_POLICE_TECH,
                description = "Motorola Solutions law enforcement technology detected. May include radios, body cameras, or command systems.",
                category = "Law Enforcement Tech",
                surveillanceType = "Police Technology",
                typicalOperator = "Law enforcement",
                legalFramework = "Standard law enforcement equipment",
                dataCollected = listOf(
                    "Varies by equipment type",
                    "Communications data",
                    "Video/audio (if camera)"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Law enforcement technology in area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 35,
                threatModifiers = emptyList()
            ),

            DeviceType.AXON_POLICE_TECH to DeviceTypeProfile(
                deviceType = DeviceType.AXON_POLICE_TECH,
                description = "Axon (formerly Taser) law enforcement technology. May include body cameras, Tasers, or fleet management systems.",
                category = "Law Enforcement Tech",
                surveillanceType = "Police Technology",
                typicalOperator = "Law enforcement",
                legalFramework = "Standard law enforcement equipment",
                dataCollected = listOf(
                    "Body camera footage",
                    "Taser deployment data",
                    "Fleet tracking"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Law enforcement technology in area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 35,
                threatModifiers = emptyList()
            ),

            DeviceType.PALANTIR_DEVICE to DeviceTypeProfile(
                deviceType = DeviceType.PALANTIR_DEVICE,
                description = "Palantir data integration system. Powerful analytics platform used by law enforcement to aggregate and analyze data from multiple sources.",
                category = "Data Analytics",
                surveillanceType = "Data Aggregation",
                typicalOperator = "Law enforcement, intelligence agencies",
                legalFramework = "Subject to agency policies and oversight",
                dataCollected = listOf(
                    "Aggregated data from multiple sources",
                    "Pattern analysis",
                    "Relationship mapping",
                    "Predictive analytics"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Advanced data analytics may be in use", RecommendationUrgency.HIGH),
                    Recommendation(2, "Multiple data sources may be correlated", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 75,
                threatModifiers = emptyList()
            ),

            // ==================== Military/Government ====================
            DeviceType.L3HARRIS_SURVEILLANCE to DeviceTypeProfile(
                deviceType = DeviceType.L3HARRIS_SURVEILLANCE,
                description = "L3Harris surveillance technology detected. Major defense contractor providing military-grade surveillance, communications, and intelligence equipment.",
                category = "Military Surveillance",
                surveillanceType = "Advanced Surveillance",
                typicalOperator = "Military, intelligence agencies, law enforcement",
                legalFramework = "Subject to classification and oversight",
                dataCollected = listOf(
                    "Advanced surveillance data",
                    "Communications interception capability",
                    "Intelligence gathering"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Military-grade surveillance equipment detected", RecommendationUrgency.HIGH),
                    Recommendation(2, "Exercise extreme caution with communications", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 90,
                threatModifiers = emptyList()
            ),

            // ==================== Ultrasonic ====================
            DeviceType.ULTRASONIC_BEACON to DeviceTypeProfile(
                deviceType = DeviceType.ULTRASONIC_BEACON,
                description = "Ultrasonic tracking beacon detected. Uses inaudible sound (18-22 kHz) to track users across devices. Detection analysis includes: amplitude fingerprinting (steady vs pulsing vs modulated patterns), source attribution against known beacon types (SilverPush, Alphonso, Signal360, LISNR, Shopkick), cross-location correlation to detect beacons following the user across multiple locations, signal-to-noise ratio analysis, and tracking likelihood scoring (0-100%). Often used for advertising attribution and cross-device identity resolution.",
                category = "Cross-Device Tracking",
                surveillanceType = "Ultrasonic Tracking",
                typicalOperator = "Advertisers, retailers, app developers",
                legalFramework = "FTC has taken action against undisclosed tracking",
                dataCollected = listOf(
                    "Cross-device tracking identifiers",
                    "Physical location association with retail/venue presence",
                    "Advertising/content attribution across devices",
                    "App usage correlation and engagement patterns",
                    "Precise indoor positioning via beacon triangulation",
                    "Dwell time at specific locations/displays",
                    "Multi-device identity linking (phone + tablet + laptop)",
                    "Temporal patterns of user presence"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Close apps with microphone permissions when not needed", RecommendationUrgency.HIGH),
                    Recommendation(2, "Check technical details for source attribution (SilverPush, Alphonso, etc.)", RecommendationUrgency.MEDIUM),
                    Recommendation(3, "If 'following user' flag is set, beacon may be tracking you across locations", RecommendationUrgency.HIGH),
                    Recommendation(4, "Consider disabling microphone access for advertising/shopping apps", RecommendationUrgency.MEDIUM),
                    Recommendation(5, "High tracking likelihood indicates active cross-device surveillance", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = "Analyze this ultrasonic beacon detection. Consider: beacon source (SilverPush, Alphonso, retail), cross-device tracking implications, following pattern across locations, and app permission recommendations.",
                threatScoreBase = 65,
                threatModifiers = listOf(
                    ThreatModifier("Following user across locations", 20, "Active tracking pattern"),
                    ThreatModifier("Known advertising network", 10, "Commercial tracking confirmed"),
                    ThreatModifier("Retail environment", -10, "Expected beacon presence")
                ),
                simpleDescription = "Hidden sounds that track you across your devices",
                simplePrivacyImpact = "Your phone, tablet, and computer activities can be linked together"
            ),

            // ==================== Satellite ====================
            DeviceType.SATELLITE_NTN to DeviceTypeProfile(
                deviceType = DeviceType.SATELLITE_NTN,
                description = "Non-terrestrial network (satellite) device detected. Could be legitimate satellite connectivity or spoofed signal.",
                category = "Satellite Communication",
                surveillanceType = "Satellite Monitoring",
                typicalOperator = "Satellite providers, potentially spoofing actors",
                legalFramework = "Subject to FCC and international regulations",
                dataCollected = listOf(
                    "Satellite communication data",
                    "Device location via satellite"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Verify satellite connectivity is expected", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Check for unexpected satellite handoffs", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 45,
                threatModifiers = listOf(
                    ThreatModifier("Unexpected in urban area", 15, "Possible spoofing")
                )
            ),

            // ==================== GNSS Threats ====================
            DeviceType.GNSS_SPOOFER to DeviceTypeProfile(
                deviceType = DeviceType.GNSS_SPOOFER,
                description = "GPS/GNSS spoofing device detected. Transmits fake satellite signals to manipulate location data. Detection analysis includes: constellation fingerprinting (expected vs observed GPS/GLONASS/Galileo/BeiDou), C/N0 baseline deviation (abnormal signal strength uniformity indicates fake signals), clock drift accumulation tracking (spoofed signals often show erratic drift patterns), satellite geometry analysis (spoofed signals may show unnaturally uniform spacing or angles), and composite spoofing likelihood scoring (0-100%). Your reported position may be inaccurate.",
                category = "GNSS Attack",
                surveillanceType = "Location Manipulation",
                typicalOperator = "Criminal actors, hostile state actors, pranksters",
                legalFramework = "Federal crime to interfere with GPS signals",
                dataCollected = listOf(
                    "Target device's reliance on GPS for location",
                    "Effectiveness of location manipulation on target",
                    "Time synchronization disruption capability",
                    "Navigation system confusion/misdirection",
                    "Geofencing bypass for location-restricted apps",
                    "False alibi generation through location spoofing",
                    "Disruption of location-based emergency services"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Your GPS location may be inaccurate - verify with visual landmarks", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Use alternative navigation (WiFi positioning, cell triangulation, maps offline)", RecommendationUrgency.IMMEDIATE),
                    Recommendation(3, "Be cautious of location-dependent apps (banking, delivery, rideshare)", RecommendationUrgency.HIGH),
                    Recommendation(4, "Check technical details for constellation analysis and spoofing likelihood", RecommendationUrgency.HIGH),
                    Recommendation(5, "If C/N0 deviation is high or clock drift erratic, spoofing is very likely", RecommendationUrgency.MEDIUM),
                    Recommendation(6, "Navigation-critical activities should be postponed until GPS normalizes", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = "Analyze this GNSS spoofing detection. Consider: spoofing likelihood score, affected constellations, C/N0 deviation significance, clock drift patterns, and safety implications for navigation.",
                threatScoreBase = 85,
                threatModifiers = listOf(
                    ThreatModifier("High spoofing likelihood", 15, "Active attack confirmed"),
                    ThreatModifier("Multiple constellations affected", 10, "Sophisticated attack"),
                    ThreatModifier("Near critical infrastructure", 10, "Potential serious implications")
                ),
                simpleDescription = "A device sending fake GPS signals to your phone",
                simplePrivacyImpact = "Your location apps may show wrong positions"
            ),

            DeviceType.GNSS_JAMMER to DeviceTypeProfile(
                deviceType = DeviceType.GNSS_JAMMER,
                description = "GPS/GNSS jamming device detected. Blocks legitimate satellite signals, preventing accurate positioning.",
                category = "GNSS Attack",
                surveillanceType = "Signal Denial",
                typicalOperator = "Criminal actors, hostile entities",
                legalFramework = "Federal crime under Communications Act",
                dataCollected = listOf(
                    "Target device's reliance on GPS for location",
                    "Effectiveness of location manipulation on target",
                    "Time synchronization disruption capability",
                    "Navigation system confusion/misdirection",
                    "Geofencing bypass for location-restricted apps",
                    "False alibi generation through location spoofing",
                    "Disruption of location-based emergency services"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "GPS signals are being blocked in this area", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Use alternative navigation methods", RecommendationUrgency.HIGH),
                    Recommendation(3, "Report to authorities if jamming is persistent", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 75,
                threatModifiers = listOf(
                    ThreatModifier("Near critical infrastructure", 15, "Serious safety concern")
                )
            ),

            // ==================== RF Threats ====================
            DeviceType.RF_JAMMER to DeviceTypeProfile(
                deviceType = DeviceType.RF_JAMMER,
                description = "RF jamming device detected. Blocks wireless communications in the area. May affect cellular, WiFi, and GPS signals.",
                category = "Signal Jamming",
                surveillanceType = "Communications Denial",
                typicalOperator = "Criminal actors, occasionally law enforcement",
                legalFramework = "Illegal under FCC regulations",
                dataCollected = listOf(
                    "No data collected - denial of service device"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Wireless communications may be blocked", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Move away from this area to regain connectivity", RecommendationUrgency.HIGH),
                    Recommendation(3, "Report persistent jamming to FCC", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 80,
                threatModifiers = listOf(
                    ThreatModifier("Near emergency services", 20, "Public safety concern")
                )
            ),

            DeviceType.HIDDEN_TRANSMITTER to DeviceTypeProfile(
                deviceType = DeviceType.HIDDEN_TRANSMITTER,
                description = "Hidden RF transmitter detected. Could be a covert listening device (bug) or other surveillance equipment.",
                category = "Covert Surveillance",
                surveillanceType = "Audio/Video Transmission",
                typicalOperator = "Unknown - potentially illegal",
                legalFramework = "Illegal to use for eavesdropping without consent",
                dataCollected = listOf(
                    "Audio transmission (if bug)",
                    "Video transmission (if camera)",
                    "Location data"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Search the area for hidden surveillance devices", RecommendationUrgency.IMMEDIATE),
                    Recommendation(2, "Use RF detector to locate the transmitter", RecommendationUrgency.HIGH),
                    Recommendation(3, "Contact authorities if found", RecommendationUrgency.HIGH)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 85,
                threatModifiers = listOf(
                    ThreatModifier("Private space", 15, "Illegal eavesdropping likely")
                )
            ),

            DeviceType.RF_INTERFERENCE to DeviceTypeProfile(
                deviceType = DeviceType.RF_INTERFERENCE,
                description = "Significant RF interference detected. May indicate jamming, environmental factors, or equipment malfunction.",
                category = "RF Anomaly",
                surveillanceType = "Signal Analysis",
                typicalOperator = "Unknown",
                legalFramework = "Intentional interference is illegal",
                dataCollected = listOf(
                    "Interference patterns"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Wireless connectivity may be degraded", RecommendationUrgency.LOW),
                    Recommendation(2, "If persistent, may indicate intentional interference", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 30,
                threatModifiers = emptyList()
            ),

            DeviceType.RF_ANOMALY to DeviceTypeProfile(
                deviceType = DeviceType.RF_ANOMALY,
                description = "Unusual RF activity pattern detected indicating potential covert surveillance infrastructure. Analysis shows anomalous hidden WiFi network characteristics including signal patterns, manufacturer clustering, temporal behavior, and channel distribution that deviate from typical residential or commercial environments.",
                category = "RF Anomaly",
                surveillanceType = "Signal Analysis",
                typicalOperator = "Law enforcement, private investigators, corporate security, government agencies",
                legalFramework = "Varies by jurisdiction; covert surveillance generally requires warrants",
                dataCollected = listOf(
                    "Presence detection via WiFi probe requests",
                    "Device MAC addresses and identifiers",
                    "Signal strength for proximity estimation",
                    "Movement patterns through coverage area",
                    "Behavioral profiling via connection patterns",
                    "Potential audio/video if hidden cameras present",
                    "Network traffic metadata if rogue AP involved"
                ),
                privacyImpact = PrivacyImpact.HIGH,
                recommendations = listOf(
                    Recommendation(1, "Note this location - high hidden network density detected", RecommendationUrgency.HIGH),
                    Recommendation(2, "Disable WiFi auto-connect to prevent rogue AP attacks", RecommendationUrgency.HIGH),
                    Recommendation(3, "Consider using VPN if connecting to any network here", RecommendationUrgency.MEDIUM),
                    Recommendation(4, "Check detection details for surveillance vendor indicators", RecommendationUrgency.MEDIUM),
                    Recommendation(5, "If persistent across visits, this may be coordinated surveillance", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = "Analyze this RF anomaly detection. Consider: hidden network patterns, manufacturer clustering, surveillance vendor indicators, temporal patterns, and potential coordinated deployment.",
                threatScoreBase = 60,
                threatModifiers = listOf(
                    ThreatModifier("Known surveillance vendor OUI", 20, "Confirmed surveillance hardware"),
                    ThreatModifier("Consistent across multiple visits", 15, "Persistent infrastructure"),
                    ThreatModifier("Near sensitive location", 10, "Targeted surveillance possible")
                )
            ),

            // ==================== Fleet/Commercial Vehicles ====================
            DeviceType.FLEET_VEHICLE to DeviceTypeProfile(
                deviceType = DeviceType.FLEET_VEHICLE,
                description = "Commercial fleet vehicle tracking system detected. May include GPS tracking, cameras, and telemetry systems.",
                category = "Fleet Management",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Commercial fleet operators",
                legalFramework = "Legal for company-owned vehicles",
                dataCollected = listOf(
                    "Vehicle location and route",
                    "Telemetry data",
                    "Driver behavior"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Commercial vehicle tracking - standard fleet management", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 20,
                threatModifiers = emptyList()
            ),

            DeviceType.SURVEILLANCE_VAN to DeviceTypeProfile(
                deviceType = DeviceType.SURVEILLANCE_VAN,
                description = "Possible mobile surveillance van detected. May contain advanced monitoring equipment including IMSI catchers, cameras, or listening devices.",
                category = "Mobile Surveillance",
                surveillanceType = "Multi-Modal Surveillance",
                typicalOperator = "Law enforcement, intelligence agencies, private investigators",
                legalFramework = "Subject to warrant requirements for most surveillance",
                dataCollected = listOf(
                    "Varies by equipment carried",
                    "Potentially communications, video, audio",
                    "Location tracking"
                ),
                privacyImpact = PrivacyImpact.CRITICAL,
                recommendations = listOf(
                    Recommendation(1, "Mobile surveillance platform may be operating", RecommendationUrgency.HIGH),
                    Recommendation(2, "Exercise caution with communications", RecommendationUrgency.HIGH),
                    Recommendation(3, "Note vehicle description and location", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 80,
                threatModifiers = listOf(
                    ThreatModifier("Stationary for extended period", 15, "Active surveillance likely")
                )
            ),

            // ==================== Misc Surveillance ====================
            DeviceType.SURVEILLANCE_INFRASTRUCTURE to DeviceTypeProfile(
                deviceType = DeviceType.SURVEILLANCE_INFRASTRUCTURE,
                description = "General surveillance infrastructure detected. May be part of a larger monitoring system.",
                category = "Infrastructure",
                surveillanceType = "General Surveillance",
                typicalOperator = "Various - depends on location",
                legalFramework = "Varies by type and location",
                dataCollected = listOf(
                    "Varies by infrastructure type"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Surveillance infrastructure present in area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 40,
                threatModifiers = emptyList()
            ),

            DeviceType.TRACKING_DEVICE to DeviceTypeProfile(
                deviceType = DeviceType.TRACKING_DEVICE,
                description = "Generic tracking device detected. May be used for asset tracking or personal surveillance.",
                category = "Tracking",
                surveillanceType = "Location Tracking",
                typicalOperator = "Unknown",
                legalFramework = "Illegal for non-consensual personal tracking",
                dataCollected = listOf(
                    "Location data",
                    "Movement patterns"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Check belongings for tracking devices", RecommendationUrgency.HIGH),
                    Recommendation(2, "If unwanted tracker found, contact authorities", RecommendationUrgency.MEDIUM)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 55,
                threatModifiers = listOf(
                    ThreatModifier("Detected multiple times", 20, "Possible stalking")
                )
            ),

            // ==================== Vendor Specific ====================
            DeviceType.PENGUIN_SURVEILLANCE to DeviceTypeProfile(
                deviceType = DeviceType.PENGUIN_SURVEILLANCE,
                description = "Penguin Surveillance system detected. Commercial surveillance platform.",
                category = "Commercial Surveillance",
                surveillanceType = "Video Surveillance",
                typicalOperator = "Businesses, security companies",
                legalFramework = "Legal for authorized surveillance",
                dataCollected = listOf(
                    "Video footage",
                    "Analytics data"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Commercial surveillance system in area", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 35,
                threatModifiers = emptyList()
            ),

            DeviceType.PIGVISION_SYSTEM to DeviceTypeProfile(
                deviceType = DeviceType.PIGVISION_SYSTEM,
                description = "Pigvision surveillance system detected. Agricultural/industrial monitoring system.",
                category = "Commercial Surveillance",
                surveillanceType = "Industrial Monitoring",
                typicalOperator = "Agricultural/industrial facilities",
                legalFramework = "Legal for property monitoring",
                dataCollected = listOf(
                    "Video footage",
                    "Environmental monitoring data"
                ),
                privacyImpact = PrivacyImpact.LOW,
                recommendations = listOf(
                    Recommendation(1, "Industrial monitoring system", RecommendationUrgency.INFORMATIONAL)
                ),
                aiPromptTemplate = null,
                threatScoreBase = 20,
                threatModifiers = emptyList()
            ),

            // ==================== Unknown/Catch-all ====================
            DeviceType.UNKNOWN_SURVEILLANCE to DeviceTypeProfile(
                deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
                description = "Unknown surveillance device detected based on wireless signature patterns. Unable to determine specific type, but characteristics suggest surveillance capability.",
                category = "Unknown",
                surveillanceType = "Unknown",
                typicalOperator = "Unknown",
                legalFramework = "Unknown - depends on device type",
                dataCollected = listOf(
                    "Device-specific data collection varies",
                    "May include location and identifiers",
                    "Behavioral patterns possible",
                    "Check device documentation"
                ),
                privacyImpact = PrivacyImpact.MEDIUM,
                recommendations = listOf(
                    Recommendation(1, "Exercise caution - unknown surveillance device", RecommendationUrgency.MEDIUM),
                    Recommendation(2, "Monitor for pattern of repeated detection", RecommendationUrgency.LOW)
                ),
                aiPromptTemplate = "Analyze this unknown surveillance device. Consider: signal characteristics, detection context, potential device types matching the signature, and appropriate precautions.",
                threatScoreBase = 45,
                threatModifiers = listOf(
                    ThreatModifier("Strong signal strength", 10, "Close proximity"),
                    ThreatModifier("Repeated detection", 15, "Persistent presence")
                )
            )
        )
    }

    private fun createDefaultProfile(deviceType: DeviceType): DeviceTypeProfile {
        return DeviceTypeProfile(
            deviceType = deviceType,
            description = "Device type detected: ${deviceType.displayName}. Limited information available for this device type.",
            category = "Uncategorized",
            surveillanceType = "Unknown",
            typicalOperator = "Unknown",
            legalFramework = null,
            dataCollected = listOf(
                "Data collection capabilities unknown",
                "Exercise standard privacy precautions"
            ),
            privacyImpact = PrivacyImpact.MEDIUM,
            recommendations = listOf(
                Recommendation(1, "Exercise standard privacy precautions", RecommendationUrgency.MEDIUM),
                Recommendation(2, "Monitor for repeated detections", RecommendationUrgency.LOW)
            ),
            aiPromptTemplate = null,
            threatScoreBase = 40,
            threatModifiers = emptyList()
        )
    }
}

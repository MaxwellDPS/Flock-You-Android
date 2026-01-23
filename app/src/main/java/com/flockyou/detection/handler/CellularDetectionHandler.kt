package com.flockyou.detection.handler

import android.util.Log
import com.flockyou.data.CellularPattern
import com.flockyou.data.CellularThresholds
import com.flockyou.data.DetectionSettingsRepository
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToSignalStrength
import com.flockyou.service.CellularMonitor
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detection Handler for Cellular Network Anomalies
 *
 * Handles detection and analysis of cellular network anomalies that may indicate
 * IMSI catchers (StingRay), cell site simulators, or other cellular surveillance devices.
 *
 * Detection Methods:
 * - CELL_ENCRYPTION_DOWNGRADE: 5G/4G to 2G downgrade (weak/no encryption)
 * - CELL_SUSPICIOUS_NETWORK: Invalid or test MCC-MNC codes
 * - CELL_TOWER_CHANGE: Unexpected cell tower changes while stationary
 * - CELL_RAPID_SWITCHING: Abnormal tower switching patterns
 * - CELL_SIGNAL_ANOMALY: Sudden signal strength changes
 * - CELL_LAC_TAC_ANOMALY: Location area code changes without cell change
 *
 * Primarily targets STINGRAY_IMSI device type detections.
 */
@Singleton
class CellularDetectionHandler @Inject constructor(
    private val detectionSettingsRepository: DetectionSettingsRepository
) : CellularDetectionHandlerInterface<CellularDetectionContext> {

    companion object {
        private const val TAG = "CellularDetectionHandler"

        // Known suspicious test/invalid MCC-MNC codes
        private val SUSPICIOUS_MCC_MNC = setOf(
            "001-01", "001-00", "001-02", "001-001", // ITU test networks
            "999-99", "999-01", "999-00",            // Reserved test networks
            "000-00", "000-01",                       // Invalid
            "002-01", "002-02",                       // Additional test codes
            "901-01", "901-18"                        // International - shouldn't appear as primary
        )

        // ==================== KNOWN IMSI CATCHER DEVICE SIGNATURES ====================

        /**
         * Real-world IMSI catcher device characteristics for AI analysis context.
         * This information helps the LLM provide expert-level analysis.
         */
        private val IMSI_CATCHER_DEVICE_INFO = """
            |KNOWN IMSI CATCHER DEVICES AND SIGNATURES:
            |
            |1. HARRIS STINGRAY / STINGRAY II
            |   - Most common law enforcement IMSI catcher in US (FBI, local police)
            |   - Manufacturer: Harris Corporation (now L3Harris)
            |   - Characteristics:
            |     * Often uses LAC values 1-10 (LAC 1 is very common)
            |     * Forces 2G (GSM) downgrade for interception
            |     * May use sequential or round Cell IDs
            |     * Ground-based, vehicle-mounted, or portable
            |   - Capabilities: IMSI collection, location tracking, call/SMS interception
            |
            |2. HARRIS HAILSTORM
            |   - Upgraded StingRay with 4G/LTE capability
            |   - Can identify devices on LTE but often still forces 2G for content interception
            |   - Similar LAC patterns to StingRay
            |   - Higher cost, used by federal agencies
            |
            |3. DRT/DRTBOX (DIGITAL RECEIVER TECHNOLOGY "DIRTBOX")
            |   - AIRPLANE-MOUNTED IMSI catcher
            |   - Used by: US Marshals Service, FBI, DEA
            |   - Characteristics:
            |     * VERY strong signal from above (-50 dBm or stronger)
            |     * Covers large geographic area
            |     * Unusual signal patterns (strength from "above")
            |   - Can collect from thousands of phones simultaneously
            |
            |4. SEPTIER IMSI CATCHER
            |   - Israeli-manufactured
            |   - Common in Middle East, Europe, and exported globally
            |   - Similar characteristics to StingRay
            |
            |5. ABILITY UNLIMITED ULIN
            |   - Passive collection variant
            |   - May not force downgrades (harder to detect)
            |   - Collects IMSI/IMEI through passive monitoring
            |
            |6. GAMMA GROUP FINFISHER
            |   - Commercial spyware suite including IMSI catcher components
            |   - Sold to governments worldwide
            |   - May include malware injection capabilities
        """.trimMargin()

        /**
         * US carrier MCC-MNC codes for validation
         */
        private val US_CARRIER_INFO = """
            |LEGITIMATE US CARRIER CODES (MCC 310/311):
            |
            |T-MOBILE USA (MCC 310):
            |  MNC: 260, 200, 210, 220, 230, 240, 250, 270, 310, 490, 660, 800, 160
            |
            |AT&T USA (MCC 310):
            |  MNC: 410, 150, 170, 380, 560, 680, 980, 070
            |
            |VERIZON WIRELESS (MCC 311):
            |  MNC: 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 012, 110
            |
            |US CELLULAR (MCC 311):
            |  MNC: 580, 581, 582, 583, 584, 585, 586, 587, 588
            |
            |If you see a US MCC (310/311) with an MNC NOT in these lists,
            |it could be a test network or IMSI catcher.
        """.trimMargin()

        /**
         * Technical background on GSM/LTE vulnerabilities for AI context
         */
        private val CELLULAR_VULNERABILITY_INFO = """
            |CELLULAR NETWORK SECURITY VULNERABILITIES:
            |
            |== WHY 2G IS DANGEROUS ==
            |GSM (2G) has a fundamental vulnerability: ONE-WAY AUTHENTICATION.
            |
            |How legitimate authentication works:
            |1. Phone connects to tower
            |2. Tower challenges phone to prove identity (IMSI/TMSI)
            |3. Phone responds with authentication
            |4. PROBLEM: Phone NEVER verifies the tower's identity
            |
            |This means ANY device broadcasting as a cell tower will be trusted.
            |
            |== ENCRYPTION VULNERABILITIES ==
            |
            |2G (GSM):
            |  - A5/0: NO encryption (plaintext)
            |  - A5/1: Weak encryption, crackable in REAL-TIME with commodity hardware
            |  - A5/2: Export cipher, even weaker (deprecated)
            |  - A5/3 (KASUMI): Stronger but rarely deployed on 2G
            |
            |3G (UMTS):
            |  - KASUMI cipher - has known weaknesses but requires significant resources
            |  - Adds mutual authentication (tower must prove identity)
            |
            |4G (LTE):
            |  - AES-256 encryption - considered secure
            |  - Mutual authentication
            |  - BUT: Can be forced to fall back to 2G
            |
            |5G (NR):
            |  - SUPI encryption (subscriber identity protected)
            |  - Stronger mutual authentication
            |  - BEST protection, but still has some vulnerabilities
            |
            |== IMSI CATCHER ATTACK FLOW ==
            |
            |1. IMSI catcher broadcasts as legitimate tower with STRONG signal
            |2. Nearby phones connect (choose strongest signal)
            |3. IMSI catcher forces 2G downgrade (disables 4G/5G)
            |4. Captures IMSI (permanent identity)
            |5. Can now:
            |   - Track location
            |   - Intercept calls/SMS (A5/1 cracked in real-time)
            |   - Inject content (fake emergency alerts)
            |   - Denial of service
            |
            |== PASSIVE VS ACTIVE IMSI CATCHERS ==
            |
            |PASSIVE:
            |  - Just listens to existing traffic
            |  - Can collect IMSI from unencrypted channels
            |  - Harder to detect
            |
            |ACTIVE:
            |  - Actively impersonates cell tower
            |  - Forces connections
            |  - Can intercept content
            |  - Detectable through the anomalies we monitor
        """.trimMargin()

        /**
         * Legal context for IMSI catcher use by region
         */
        private val LEGAL_CONTEXT_INFO = """
            |LEGAL CONTEXT FOR IMSI CATCHER USE:
            |
            |== UNITED STATES ==
            |Federal:
            |  - Law enforcement generally needs a warrant (since 2015 DOJ policy)
            |  - FBI, DEA, US Marshals, Secret Service all use them
            |  - Often use "pen register" orders (lower standard than warrant)
            |
            |State laws vary significantly:
            |  - CALIFORNIA: Requires warrant, public reporting
            |  - WASHINGTON: Requires warrant
            |  - MARYLAND: Generally requires warrant
            |  - ILLINOIS: Electronic surveillance requires two-party consent
            |  - Many states have NO specific regulations
            |
            |CBP/ICE:
            |  - Border Patrol uses them at borders/airports
            |  - Lower warrant requirements at "border zones" (100 miles)
            |
            |Local police:
            |  - Many departments have StingRays
            |  - Often acquired with federal grants
            |  - Usage often kept secret (NDA with Harris Corp)
            |
            |== EUROPEAN UNION ==
            |Generally more restricted:
            |  - GDPR implications for mass surveillance
            |  - Most countries require judicial authorization
            |  - UK: Regulated under Investigatory Powers Act
            |  - Germany: BKA uses them with judicial oversight
            |
            |== IF YOU BELIEVE YOU'RE BEING SURVEILLED ==
            |1. Document time, location, and detection details
            |2. Note any pattern (same location, following you)
            |3. Consider filing FOIA requests with local police/FBI
            |4. Contact digital rights organizations (EFF, ACLU)
            |5. If immediate threat: enable airplane mode, use encrypted apps only
        """.trimMargin()

        // IMSI catcher score thresholds - aligned with severity levels
        // Score 90-100 = CRITICAL (immediate threat)
        // Score 70-89 = HIGH (confirmed surveillance indicators)
        // Score 50-69 = MEDIUM (likely surveillance equipment)
        // Score 30-49 = LOW (possible, continue monitoring)
        // Score 0-29 = INFO (notable but not threatening)
        private const val IMSI_CRITICAL_THRESHOLD = 90
        private const val IMSI_HIGH_THRESHOLD = 70
        private const val IMSI_MEDIUM_THRESHOLD = 50
        private const val IMSI_LOW_THRESHOLD = 30
    }

    /**
     * Tracks last detection time per method for rate limiting.
     * Thread-safe: Uses ConcurrentHashMap for safe multi-threaded access.
     */
    private val lastDetectionTime = ConcurrentHashMap<DetectionMethod, Long>()

    /**
     * Check if this handler can process the given context.
     */
    override fun canHandle(context: CellularDetectionContext): Boolean {
        return context.anomalyType in listOf(
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE,
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK,
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE,
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE,
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING,
            CellularMonitor.AnomalyType.SIGNAL_SPIKE,
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY,
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA
        )
    }

    /**
     * Process a CellularDetectionContext and return a Detection if warranted.
     */
    override suspend fun handle(context: CellularDetectionContext): Detection? {
        val settings = detectionSettingsRepository.settings.first()

        // Check if cellular detection is enabled globally
        if (!settings.enableCellularDetection) {
            Log.d(TAG, "Cellular detection disabled globally")
            return null
        }

        val detectionMethod = mapAnomalyTypeToMethod(context.anomalyType)

        // Check if this detection method is enabled
        val pattern = mapMethodToPattern(detectionMethod)
        if (pattern != null && pattern !in settings.enabledCellularPatterns) {
            Log.d(TAG, "Detection method ${detectionMethod.name} is disabled, skipping")
            return null
        }

        // Rate limiting check
        val thresholds = settings.cellularThresholds
        val now = System.currentTimeMillis()
        val lastTime = lastDetectionTime[detectionMethod] ?: 0L
        if (now - lastTime < thresholds.minAnomalyIntervalMs) {
            Log.d(TAG, "Rate limiting detection ${detectionMethod.name}")
            return null
        }
        lastDetectionTime[detectionMethod] = now

        // Check severity threshold - only HIGH and CRITICAL generate detections by default
        if (context.severity != null && context.severity !in listOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL)) {
            if (context.severity == ThreatLevel.MEDIUM) {
                if (!meetsThresholds(context, thresholds)) {
                    Log.d(TAG, "Anomaly doesn't meet thresholds: ${context.anomalyType}")
                    return null
                }
            } else {
                Log.d(TAG, "Anomaly severity too low: ${context.severity}")
                return null
            }
        }

        // Build the detection
        val detection = buildDetection(context, detectionMethod)

        Log.i(TAG, "Created detection: ${detection.deviceName}, threat=${detection.threatLevel}, " +
            "IMSI score=${context.imsiCatcherScore}%")

        return detection
    }

    /**
     * Generate an enriched AI prompt for cellular anomaly analysis.
     *
     * This provides COMPREHENSIVE context for LLM analysis including:
     * - Real-world IMSI catcher device signatures and characteristics
     * - Technical background on GSM/LTE vulnerabilities
     * - Specific indicators detected and their significance
     * - Environmental context (movement, location, network history)
     * - User verification methods
     * - Legal context by region
     * - Actionable recommendations based on threat level
     *
     * The goal is to enable the AI to provide EXPERT-LEVEL analysis
     * comparable to a cellular security specialist.
     */
    override fun generateAiPrompt(context: CellularDetectionContext, detection: Detection): String {
        return buildString {
            appendLine("=" .repeat(70))
            appendLine("CELLULAR ANOMALY ANALYSIS - EXPERT AI CONTEXT")
            appendLine("=" .repeat(70))
            appendLine()

            // ==================== SECTION 1: EXECUTIVE SUMMARY ====================
            appendLine("== EXECUTIVE SUMMARY ==")
            appendLine()
            appendLine("IMSI Catcher Likelihood Score: ${context.imsiCatcherScore}%")
            appendLine("Threat Level: ${detection.threatLevel.displayName}")
            appendLine("Detection Type: ${mapAnomalyTypeToMethod(context.anomalyType).displayName}")
            appendLine("Assessment: ${getImsiAssessment(context.imsiCatcherScore)}")
            appendLine()

            // ==================== SECTION 2: WHAT WAS DETECTED ====================
            appendLine("== WHAT WAS DETECTED ==")
            appendLine()
            appendLine("Primary Anomaly: ${context.anomalyType.name}")
            appendLine("Confidence: ${context.confidence?.displayName ?: "Unknown"}")
            appendLine()
            appendLine(getAnomalyExplanation(context.anomalyType))
            appendLine()

            // Contributing factors
            if (context.contributingFactors.isNotEmpty()) {
                appendLine("Contributing Factors:")
                context.contributingFactors.forEachIndexed { index, factor ->
                    appendLine("  ${index + 1}. $factor")
                }
                appendLine()
            }

            // ==================== SECTION 3: NETWORK TECHNICAL DETAILS ====================
            appendLine("== NETWORK TECHNICAL DETAILS ==")
            appendLine()
            appendLine("Current Network Type: ${context.networkType}")
            appendLine("MCC-MNC: ${context.mcc}-${context.mnc}")
            appendLine("Cell ID: ${context.cellId ?: "Unknown"}")
            context.previousCellId?.let { appendLine("Previous Cell ID: $it") }
            context.lac?.let { lac ->
                appendLine("Location Area Code (LAC): $lac")
                if (lac in 0..10) {
                    appendLine("  WARNING: LAC $lac is in suspicious range (1-10)")
                    appendLine("  StingRay devices often use LAC 1 or very low LAC values")
                }
            }
            context.tac?.let { tac ->
                appendLine("Tracking Area Code (TAC): $tac")
                if (tac in 0..5) {
                    appendLine("  WARNING: TAC $tac is in suspicious range")
                }
            }
            appendLine("Roaming: ${if (context.isRoaming) "Yes" else "No"}")
            appendLine()

            // MCC-MNC Analysis
            val mccMnc = "${context.mcc}-${context.mnc}"
            if (mccMnc in SUSPICIOUS_MCC_MNC) {
                appendLine("CRITICAL: MCC-MNC $mccMnc is a KNOWN TEST/INVALID CODE")
                appendLine("This should NEVER appear on legitimate networks!")
                appendLine()
            }

            // ==================== SECTION 4: SIGNAL ANALYSIS ====================
            appendLine("== SIGNAL ANALYSIS ==")
            appendLine()
            appendLine("Current Signal: ${context.signalStrength} dBm (${getSignalQuality(context.signalStrength)})")

            // Check for suspiciously strong signal (DRTbox characteristic)
            if (context.signalStrength >= -55) {
                appendLine("  ALERT: Unusually strong signal!")
                appendLine("  Signals this strong may indicate:")
                appendLine("    - Very close proximity to tower (unusual)")
                appendLine("    - DRTbox/airplane-mounted IMSI catcher")
                appendLine("    - Close-proximity ground-based IMSI catcher")
            }

            context.previousSignalStrength?.let { prev ->
                val delta = context.signalStrength - prev
                appendLine("Signal Change: ${if (delta >= 0) "+" else ""}$delta dBm")
                if (delta > 20) {
                    appendLine("  WARNING: Large signal spike detected (+$delta dBm)")
                    appendLine("  IMSI catchers broadcast strong signals to attract phones")
                }
            }
            appendLine()

            // ==================== SECTION 5: ENCRYPTION ANALYSIS ====================
            appendLine("== ENCRYPTION STATUS ==")
            appendLine()
            appendLine("Current: ${context.encryptionType.displayName}")
            appendLine("Security: ${context.encryptionType.description}")

            context.previousNetworkType?.let { prevNet ->
                appendLine("Previous Network: $prevNet")
            }

            if (context.encryptionType == EncryptionType.WEAK_2G ||
                context.encryptionType == EncryptionType.NONE) {
                appendLine()
                appendLine("*** ENCRYPTION VULNERABILITY DETECTED ***")
                appendLine()
                appendLine("Current encryption: ${context.encryptionType.displayName}")
                appendLine("Vulnerability: ${getEncryptionVulnerability(context.encryptionType)}")
                appendLine()
                appendLine("WHY THIS MATTERS:")
                appendLine("- 2G uses one-way authentication (phone cannot verify tower)")
                appendLine("- A5/1 cipher can be cracked in REAL-TIME with $1000 hardware")
                appendLine("- Voice calls and SMS can be intercepted")
                appendLine("- This is the PRIMARY method IMSI catchers use for interception")
            }
            appendLine()

            // Downgrade chain
            context.encryptionDowngradeChain?.let { chain ->
                if (chain.size > 1) {
                    appendLine("Network Generation History: ${chain.joinToString(" -> ")}")
                    if (chain.last() == "2G") {
                        appendLine("  WARNING: Progressive downgrade to 2G")
                        appendLine("  This pattern is CLASSIC StingRay behavior")
                    }
                    appendLine()
                }
            }

            // ==================== SECTION 6: MOVEMENT CONTEXT ====================
            context.movementType?.let { movement ->
                appendLine("== MOVEMENT CONTEXT ==")
                appendLine()
                appendLine("Classification: ${movement.displayName}")
                context.speedKmh?.let { appendLine("Speed: ${String.format("%.1f", it)} km/h") }
                context.distanceMeters?.let { appendLine("Distance: ${String.format("%.1f", it)} meters") }
                appendLine()

                when (movement) {
                    MovementType.STATIONARY -> {
                        appendLine("Analysis: You appear stationary.")
                        appendLine("- Cell changes while stationary CAN be normal (load balancing)")
                        appendLine("- BUT: Multiple changes or changes to unknown towers are suspicious")
                        appendLine("- AND: Changes combined with encryption downgrade are very suspicious")
                    }
                    MovementType.IMPOSSIBLE -> {
                        appendLine("*** CRITICAL: IMPOSSIBLE MOVEMENT DETECTED ***")
                        appendLine("Calculated speed: ${String.format("%.0f", context.speedKmh)} km/h")
                        appendLine()
                        appendLine("This could indicate:")
                        appendLine("  1. Mobile IMSI catcher (vehicle/aircraft-mounted)")
                        appendLine("  2. Location data spoofing/GPS interference")
                        appendLine("  3. Time synchronization issue (less likely)")
                    }
                    MovementType.VEHICLE, MovementType.HIGH_SPEED_VEHICLE -> {
                        appendLine("Analysis: You appear to be traveling.")
                        appendLine("- Cell tower changes are EXPECTED when moving")
                        appendLine("- This reduces suspicion for simple handoffs")
                        appendLine("- BUT: Encryption downgrade while moving is still suspicious")
                    }
                    else -> {
                        appendLine("Movement appears normal for the detected speed.")
                    }
                }
                appendLine()
            }

            // ==================== SECTION 7: CELL TRUST ANALYSIS ====================
            context.cellTrustScore?.let { trust ->
                appendLine("== CELL TOWER FAMILIARITY ==")
                appendLine()
                appendLine("Trust Score: $trust%")
                appendLine("Status: ${getTrustStatus(trust)}")
                context.cellSeenCount?.let { appendLine("Times Seen: $it") }
                context.isInFamiliarArea?.let {
                    appendLine("In Familiar Area: ${if (it) "Yes" else "No"}")
                }

                if (trust < 30) {
                    appendLine()
                    appendLine("NOTE: This cell tower is UNFAMILIAR")
                    appendLine("- Never seen before or rarely seen")
                    appendLine("- New towers in familiar areas warrant attention")
                    appendLine("- Could be: new legitimate tower, or temporary surveillance")
                }
                appendLine()
            }

            // ==================== SECTION 8: REAL-WORLD CONTEXT ====================
            appendLine("== REAL-WORLD IMSI CATCHER KNOWLEDGE ==")
            appendLine()
            appendLine(IMSI_CATCHER_DEVICE_INFO)
            appendLine()

            // ==================== SECTION 9: TECHNICAL BACKGROUND ====================
            appendLine("== TECHNICAL BACKGROUND ==")
            appendLine()
            appendLine(CELLULAR_VULNERABILITY_INFO)
            appendLine()

            // ==================== SECTION 10: CARRIER VALIDATION ====================
            appendLine("== CARRIER INFORMATION ==")
            appendLine()
            appendLine(US_CARRIER_INFO)
            appendLine()

            // ==================== SECTION 11: USER VERIFICATION METHODS ====================
            appendLine("== HOW TO VERIFY THIS DETECTION ==")
            appendLine()
            appendLine("The user can perform these checks to validate the detection:")
            appendLine()
            appendLine("1. CHECK SERVING CELL INFO:")
            appendLine("   - Samsung: Dial *#0011# for ServiceMode")
            appendLine("   - Other Android: Use apps like 'Network Cell Info Lite'")
            appendLine("   - Look for: Cell ID, LAC/TAC, MCC-MNC, network type")
            appendLine()
            appendLine("2. VERIFY CELL EXISTS IN PUBLIC DATABASES:")
            appendLine("   - cellmapper.net - Community-mapped cell towers")
            appendLine("   - opencellid.org - Open database of cell locations")
            appendLine("   - If the Cell ID doesn't appear, it may be fake")
            appendLine()
            appendLine("3. CHECK IF CELL APPEARS IN MULTIPLE LOCATIONS:")
            appendLine("   - Real towers are FIXED - they don't move")
            appendLine("   - If same Cell ID appears in different locations = FAKE")
            appendLine()
            appendLine("4. MONITOR OVER TIME:")
            appendLine("   - Does this cell appear consistently or sporadically?")
            appendLine("   - Does it only appear during certain events?")
            appendLine()
            appendLine("5. CHECK TIMING ADVANCE (if available):")
            appendLine("   - TA indicates distance to tower")
            appendLine("   - IMSI catchers may have inconsistent TA values")
            appendLine()
            appendLine("6. DOCUMENT FOR RECORDS:")
            appendLine("   - Note time, location, cell details")
            appendLine("   - Useful for FOIA requests or legal action")
            appendLine()

            // ==================== SECTION 12: ENVIRONMENTAL CONTEXT ====================
            appendLine("== ENVIRONMENTAL CONTEXT CONSIDERATIONS ==")
            appendLine()
            appendLine("IMSI catchers are more commonly deployed:")
            appendLine()
            appendLine("HIGH LIKELIHOOD LOCATIONS:")
            appendLine("  - Protests, demonstrations, political gatherings")
            appendLine("  - Near government buildings (federal, state, local)")
            appendLine("  - Airports, border crossings (CBP deployment)")
            appendLine("  - Prisons/jails (contraband phone detection)")
            appendLine("  - High-profile events (conferences, summits)")
            appendLine()
            appendLine("MODERATE LIKELIHOOD:")
            appendLine("  - Major public events (concerts, sports)")
            appendLine("  - Tourist areas in major cities")
            appendLine("  - Near foreign embassies/consulates")
            appendLine()
            appendLine("LOWER LIKELIHOOD:")
            appendLine("  - Residential neighborhoods (unless targeted)")
            appendLine("  - Rural areas")
            appendLine("  - Late night in quiet areas (unless following someone)")
            appendLine()
            appendLine("Ask the user about their current location context.")
            appendLine()

            // ==================== SECTION 13: LEGAL CONTEXT ====================
            appendLine("== LEGAL CONTEXT ==")
            appendLine()
            appendLine(LEGAL_CONTEXT_INFO)
            appendLine()

            // ==================== SECTION 14: RECOMMENDATIONS ====================
            appendLine("== RECOMMENDED ACTIONS ==")
            appendLine()
            appendLine(getRecommendations(context))
            appendLine()

            // ==================== SECTION 15: AI ANALYSIS INSTRUCTIONS ====================
            appendLine("=" .repeat(70))
            appendLine("INSTRUCTIONS FOR AI ANALYSIS")
            appendLine("=" .repeat(70))
            appendLine()
            appendLine("Based on all the above information, provide the user with:")
            appendLine()
            appendLine("1. ASSESSMENT: Is this likely an IMSI catcher or normal network behavior?")
            appendLine("   Consider all factors: score, encryption, movement, cell familiarity")
            appendLine()
            appendLine("2. SPECIFIC ANALYSIS: What specific indicators are most concerning?")
            appendLine("   Reference the real-world device signatures if applicable")
            appendLine()
            appendLine("3. CONTEXT QUESTIONS: Ask about user's location/situation")
            appendLine("   - Are they near any high-risk locations?")
            appendLine("   - Is this part of a pattern they've noticed?")
            appendLine()
            appendLine("4. VERIFICATION STEPS: Guide them through verification")
            appendLine("   - Recommend specific checks from the verification section")
            appendLine()
            appendLine("5. ACTIONABLE ADVICE: What should they do RIGHT NOW?")
            appendLine("   - Based on threat level, give specific guidance")
            appendLine("   - For HIGH/CRITICAL: immediate protective actions")
            appendLine("   - For MEDIUM: monitoring and precautions")
            appendLine("   - For LOW: awareness and documentation")
            appendLine()
            appendLine("6. EDUCATION: Help them understand what's happening")
            appendLine("   - Explain the technical aspects in accessible terms")
            appendLine("   - Help them make informed decisions")
            appendLine()
            appendLine("Be direct, specific, and actionable. Avoid vague warnings.")
            appendLine("If this appears to be a false positive, explain why.")
        }
    }

    /**
     * Get a user-friendly explanation of the anomaly type.
     */
    private fun getAnomalyExplanation(type: CellularMonitor.AnomalyType): String {
        return when (type) {
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE ->
                "Your phone was forced to use an older, weaker network (like 2G) that has " +
                "known encryption vulnerabilities. IMSI catchers often force this downgrade " +
                "to intercept communications that would otherwise be encrypted."

            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK ->
                "Your phone connected to a network using test or invalid identifiers. " +
                "Legitimate cell networks never use these codes. This is a strong indicator " +
                "of surveillance equipment or a misconfigured test system."

            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE ->
                "Your phone switched to a different cell tower even though you weren't moving. " +
                "Single occurrences are often normal (network load balancing), but repeated " +
                "changes or changes to unfamiliar towers warrant attention."

            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING ->
                "Your phone is switching between cell towers more frequently than normal. " +
                "This can indicate competing signals from surveillance equipment trying to " +
                "capture your connection."

            CellularMonitor.AnomalyType.SIGNAL_SPIKE ->
                "Your phone's signal strength jumped unusually high. IMSI catchers broadcast " +
                "strong signals to attract nearby phones. However, this can also occur near " +
                "legitimate towers or with antenna alignment."

            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA ->
                "A cell tower appeared in an area where you've been before, but this specific " +
                "tower has never been seen. This could indicate a new legitimate tower or " +
                "a temporary surveillance device."

            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY ->
                "The cell tower's location area code changed without actually changing towers. " +
                "This is technically unusual and can indicate network manipulation."

            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE ->
                "Your phone switched to a different cell tower. This is usually normal during " +
                "movement, but can be suspicious when combined with other indicators."
        }
    }

    /**
     * Convert a CellularAnomaly directly to Detection.
     */
    suspend fun convertAnomalyToDetection(anomaly: CellularMonitor.CellularAnomaly): Detection? {
        // Build context from anomaly
        val context = CellularDetectionContext(
            anomalyType = anomaly.type,
            mcc = anomaly.mccMnc?.split("-")?.getOrNull(0),
            mnc = anomaly.mccMnc?.split("-")?.getOrNull(1),
            cellId = anomaly.cellId?.toLong(),
            previousCellId = anomaly.previousCellId?.toLong(),
            lac = null, // Not available directly from anomaly
            tac = null,
            signalStrength = anomaly.signalStrength,
            previousSignalStrength = anomaly.previousSignalStrength,
            networkType = anomaly.networkType,
            previousNetworkType = anomaly.previousNetworkType,
            encryptionType = mapNetworkTypeToEncryption(anomaly.networkType),
            isRoaming = false, // Not available directly
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            timestamp = anomaly.timestamp,
            imsiCatcherScore = calculateImsiScoreFromAnomaly(anomaly),
            contributingFactors = anomaly.contributingFactors,
            confidence = anomaly.confidence,
            severity = anomaly.severity
        )

        return handle(context)
    }

    /**
     * Calculate IMSI catcher likelihood score (0-100).
     */
    fun calculateImsiCatcherScore(context: CellularDetectionContext): Int {
        var score = 0

        // Base score from anomaly type
        score += getAnomalyBaseScore(context.anomalyType)

        // Encryption downgrade is highly suspicious
        if (context.encryptionType == EncryptionType.WEAK_2G ||
            context.encryptionType == EncryptionType.NONE) {
            score += 25
        }

        // Signal spike with tower change
        context.previousSignalStrength?.let { prev ->
            val delta = context.signalStrength - prev
            if (delta > 20) { // signalSpikeThreshold default
                score += 20
            }
        }

        // Unknown/untrusted cell
        if (context.cellTrustScore != null && context.cellTrustScore < 30) {
            score += 15
        }

        // Stationary but cell changed
        if (context.movementType == MovementType.STATIONARY &&
            context.cellId != context.previousCellId) {
            score += 15
        }

        // Impossible movement speed
        if (context.movementType == MovementType.IMPOSSIBLE) {
            score += 20
        }

        // Test network MCC-MNC
        val mccMnc = "${context.mcc}-${context.mnc}"
        if (mccMnc in SUSPICIOUS_MCC_MNC) {
            score += 30
        }

        return score.coerceIn(0, 100)
    }

    // ==================== Private Helper Methods ====================

    private fun meetsThresholds(context: CellularDetectionContext, thresholds: CellularThresholds): Boolean {
        // Only report if IMSI score reaches at least LOW concern level (30+)
        // This prevents noise from being reported as detections
        return context.imsiCatcherScore >= IMSI_LOW_THRESHOLD
    }

    private fun buildDetection(context: CellularDetectionContext, method: DetectionMethod): Detection {
        val anomalyEmoji = getAnomalyEmoji(context.anomalyType)
        val anomalyName = getAnomalyDisplayName(context.anomalyType)
        val deviceName = "$anomalyEmoji $anomalyName"

        val threatLevel = determineThreatLevel(context)
        val threatScore = context.imsiCatcherScore

        // Build matched patterns string from contributing factors
        val matchedPatterns = context.contributingFactors.joinToString(", ")

        return Detection(
            id = UUID.randomUUID().toString(),
            timestamp = context.timestamp,
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = method,
            deviceType = DeviceType.STINGRAY_IMSI,
            deviceName = deviceName,
            macAddress = null,
            ssid = null,
            rssi = context.signalStrength,
            signalStrength = rssiToSignalStrength(context.signalStrength),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = threatLevel,
            threatScore = threatScore,
            manufacturer = "Cell: ${context.cellId ?: "Unknown"}",
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = matchedPatterns,
            rawData = buildRawDataString(context),
            isActive = true,
            seenCount = 1,
            lastSeenTimestamp = context.timestamp
        )
    }

    private fun buildRawDataString(context: CellularDetectionContext): String {
        return buildString {
            appendLine("MCC-MNC: ${context.mcc}-${context.mnc}")
            appendLine("Cell ID: ${context.cellId}")
            appendLine("Network: ${context.networkType}")
            appendLine("Signal: ${context.signalStrength} dBm")
            appendLine("Encryption: ${context.encryptionType.displayName}")
            appendLine("IMSI Score: ${context.imsiCatcherScore}%")
            context.movementType?.let { appendLine("Movement: ${it.displayName}") }
        }
    }

    private fun determineThreatLevel(context: CellularDetectionContext): ThreatLevel {
        // Use provided severity if available
        context.severity?.let { return it }

        // FIXED: Severity MUST match the IMSI likelihood score
        // This ensures consistency between what we report and the actual threat level
        return when {
            context.imsiCatcherScore >= IMSI_CRITICAL_THRESHOLD -> ThreatLevel.CRITICAL  // 90-100%
            context.imsiCatcherScore >= IMSI_HIGH_THRESHOLD -> ThreatLevel.HIGH          // 70-89%
            context.imsiCatcherScore >= IMSI_MEDIUM_THRESHOLD -> ThreatLevel.MEDIUM      // 50-69%
            context.imsiCatcherScore >= IMSI_LOW_THRESHOLD -> ThreatLevel.LOW            // 30-49%
            else -> ThreatLevel.INFO                                                      // 0-29%
        }
    }

    private fun mapAnomalyTypeToMethod(type: CellularMonitor.AnomalyType): DetectionMethod {
        return when (type) {
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> DetectionMethod.CELL_ENCRYPTION_DOWNGRADE
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> DetectionMethod.CELL_SUSPICIOUS_NETWORK
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> DetectionMethod.CELL_RAPID_SWITCHING
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> DetectionMethod.CELL_SIGNAL_ANOMALY
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> DetectionMethod.CELL_LAC_TAC_ANOMALY
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> DetectionMethod.CELL_TOWER_CHANGE
        }
    }

    private fun mapMethodToPattern(method: DetectionMethod): CellularPattern? {
        return when (method) {
            DetectionMethod.CELL_ENCRYPTION_DOWNGRADE -> CellularPattern.ENCRYPTION_DOWNGRADE
            DetectionMethod.CELL_SUSPICIOUS_NETWORK -> CellularPattern.SUSPICIOUS_NETWORK_ID
            DetectionMethod.CELL_TOWER_CHANGE -> CellularPattern.CELL_ID_CHANGE
            DetectionMethod.CELL_RAPID_SWITCHING -> CellularPattern.RAPID_CELL_SWITCHING
            DetectionMethod.CELL_SIGNAL_ANOMALY -> CellularPattern.SIGNAL_SPIKE
            DetectionMethod.CELL_LAC_TAC_ANOMALY -> CellularPattern.LAC_TAC_ANOMALY
            else -> null
        }
    }

    /**
     * Get base IMSI score for anomaly type.
     *
     * CALIBRATED BASE SCORES (aligned with CellularMonitor):
     * - Base scores are intentionally LOW for common events that need context
     * - A single cell change while stationary is common (network optimization)
     * - Multiple factors combined increase the total score appropriately
     */
    private fun getAnomalyBaseScore(type: CellularMonitor.AnomalyType): Int {
        return when (type) {
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> 90     // Test networks = immediate concern
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> 60   // 2G downgrade is concerning, but needs context
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> 25  // Needs multiple factors
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> 15     // Very common, needs pattern analysis
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> 20       // Needs frequency context
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> 20            // Technical anomaly, needs context
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> 15               // Common near towers, needs context
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> 10          // Very common, baseline event
        }
    }

    private fun getAnomalyEmoji(type: CellularMonitor.AnomalyType): String {
        return when (type) {
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> "📶"
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> "🗼"
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> "🔓"
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> "⚠️"
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> "❓"
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> "🔄"
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> "📍"
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> "🚫"
        }
    }

    private fun getAnomalyDisplayName(type: CellularMonitor.AnomalyType): String {
        return when (type) {
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> "Sudden Signal Spike"
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> "Cell Tower Change"
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> "Encryption Downgrade"
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> "Suspicious Network ID"
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> "Unknown Cell in Familiar Area"
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> "Rapid Cell Switching"
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> "Location Area Anomaly"
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> "Cell Changed While Stationary"
        }
    }

    private fun mapNetworkTypeToEncryption(networkType: String): EncryptionType {
        return when {
            networkType.contains("5G", ignoreCase = true) ||
            networkType.contains("NR", ignoreCase = true) -> EncryptionType.STRONG_5G_LTE
            networkType.contains("4G", ignoreCase = true) ||
            networkType.contains("LTE", ignoreCase = true) -> EncryptionType.STRONG_5G_LTE
            networkType.contains("3G", ignoreCase = true) ||
            networkType.contains("UMTS", ignoreCase = true) ||
            networkType.contains("HSPA", ignoreCase = true) -> EncryptionType.MODERATE_3G
            networkType.contains("2G", ignoreCase = true) ||
            networkType.contains("GSM", ignoreCase = true) ||
            networkType.contains("EDGE", ignoreCase = true) ||
            networkType.contains("GPRS", ignoreCase = true) -> EncryptionType.WEAK_2G
            else -> EncryptionType.UNKNOWN
        }
    }

    private fun calculateImsiScoreFromAnomaly(anomaly: CellularMonitor.CellularAnomaly): Int {
        var score = getAnomalyBaseScore(anomaly.type)

        // Boost score based on confidence
        when (anomaly.confidence) {
            CellularMonitor.AnomalyConfidence.CRITICAL -> score += 20
            CellularMonitor.AnomalyConfidence.HIGH -> score += 10
            CellularMonitor.AnomalyConfidence.MEDIUM -> score += 5
            CellularMonitor.AnomalyConfidence.LOW -> { /* no boost */ }
        }

        // Boost for multiple contributing factors
        if (anomaly.contributingFactors.size >= 4) score += 15
        else if (anomaly.contributingFactors.size >= 3) score += 10
        else if (anomaly.contributingFactors.size >= 2) score += 5

        return score.coerceIn(0, 100)
    }

    private fun getSignalQuality(signalDbm: Int): String {
        return when {
            signalDbm >= -70 -> "Excellent"
            signalDbm >= -85 -> "Good"
            signalDbm >= -100 -> "Fair"
            signalDbm >= -110 -> "Poor"
            else -> "Very Poor"
        }
    }

    private fun getTrustStatus(trustScore: Int): String {
        return when {
            trustScore >= 80 -> "Trusted (frequently seen)"
            trustScore >= 60 -> "Known (seen before)"
            trustScore >= 30 -> "Unfamiliar (rarely seen)"
            else -> "Unknown (never seen before)"
        }
    }

    private fun getEncryptionVulnerability(encryption: EncryptionType): String {
        return when (encryption) {
            EncryptionType.NONE -> "No encryption - all communications in plaintext"
            EncryptionType.WEAK_2G -> "A5/1 cipher can be cracked in real-time with commodity hardware"
            EncryptionType.MODERATE_3G -> "KASUMI cipher has known weaknesses but requires significant resources"
            EncryptionType.STRONG_5G_LTE -> "AES-256 encryption - considered secure"
            EncryptionType.UNKNOWN -> "Unknown encryption status"
        }
    }

    private fun getImsiAssessment(score: Int): String {
        return when {
            score >= 90 -> "CRITICAL - Strong indicators of active IMSI catcher/cell site simulator. Immediate action recommended."
            score >= 70 -> "HIGH - Multiple confirmed surveillance indicators. Exercise caution with communications."
            score >= 50 -> "MEDIUM - Likely surveillance equipment detected. Monitor for additional indicators."
            score >= 30 -> "LOW - Possible surveillance activity. Continue monitoring but no immediate action needed."
            else -> "INFO - Normal cellular behavior with minor anomalies. No action required."
        }
    }

    private fun getRecommendations(context: CellularDetectionContext): String {
        return buildString {
            when {
                context.imsiCatcherScore >= 90 -> {
                    appendLine("*** CRITICAL THREAT - IMMEDIATE ACTIONS REQUIRED ***")
                    appendLine()
                    appendLine("STEP 1 - DISCONNECT NOW:")
                    appendLine("   - Enable airplane mode IMMEDIATELY")
                    appendLine("   - This breaks connection to the suspected IMSI catcher")
                    appendLine()
                    appendLine("STEP 2 - PROTECT YOUR COMMUNICATIONS:")
                    appendLine("   - DO NOT make phone calls (can be intercepted)")
                    appendLine("   - DO NOT send SMS (can be intercepted)")
                    appendLine("   - If you must communicate, use WiFi + VPN + Signal/WhatsApp")
                    appendLine()
                    appendLine("STEP 3 - MOVE TO SAFETY:")
                    appendLine("   - Leave the immediate area if possible")
                    appendLine("   - Move at least 500 meters away")
                    appendLine("   - Briefly enable cellular to check if anomaly persists")
                    appendLine()
                    appendLine("STEP 4 - DOCUMENT EVERYTHING:")
                    appendLine("   - Screenshot this detection")
                    appendLine("   - Note: time, exact location, what you were doing")
                    appendLine("   - This could be evidence for legal action or FOIA")
                    appendLine()
                    appendLine("STEP 5 - VERIFY THE THREAT:")
                    appendLine("   - Check cell ID on cellmapper.net or opencellid.org")
                    appendLine("   - Samsung users: dial *#0011# for service mode")
                    appendLine("   - If cell doesn't exist in databases, highly likely fake")
                    appendLine()
                    appendLine("STEP 6 - CONSIDER REPORTING:")
                    appendLine("   - Contact EFF (eff.org) or ACLU if you believe you're targeted")
                    appendLine("   - File FOIA request with local police for StingRay records")
                    appendLine("   - Document pattern if this happens repeatedly")
                }
                context.imsiCatcherScore >= 70 -> {
                    appendLine("*** HIGH THREAT - TAKE PROTECTIVE ACTION ***")
                    appendLine()
                    appendLine("IMMEDIATE PRECAUTIONS:")
                    appendLine("   - Avoid making sensitive phone calls")
                    appendLine("   - Do not send SMS with sensitive content")
                    appendLine("   - Use Signal, WhatsApp, or other E2E encrypted apps")
                    appendLine()
                    appendLine("VERIFICATION STEPS:")
                    appendLine("   - Check if cell ID exists: cellmapper.net, opencellid.org")
                    appendLine("   - Samsung: *#0011# shows network details")
                    appendLine("   - Note LAC value - if 1-10, very suspicious")
                    appendLine()
                    appendLine("IF ENCRYPTION DOWNGRADE DETECTED:")
                    appendLine("   - Your phone may have been forced to 2G")
                    appendLine("   - Voice calls on 2G can be intercepted in real-time")
                    appendLine("   - Consider airplane mode for any sensitive discussions")
                    appendLine()
                    appendLine("CONTEXT CHECK:")
                    appendLine("   - Are you near: protest, government building, airport, prison?")
                    appendLine("   - These locations commonly have IMSI catcher deployment")
                    appendLine()
                    appendLine("DOCUMENTATION:")
                    appendLine("   - Save this detection for your records")
                    appendLine("   - If pattern repeats, you may be specifically targeted")
                }
                context.imsiCatcherScore >= 50 -> {
                    appendLine("*** MODERATE CONCERN - HEIGHTENED AWARENESS ***")
                    appendLine()
                    appendLine("CURRENT SITUATION:")
                    appendLine("   - Multiple indicators suggest possible surveillance")
                    appendLine("   - Could still be a false positive from network issues")
                    appendLine("   - Worth monitoring but not emergency")
                    appendLine()
                    appendLine("RECOMMENDED ACTIONS:")
                    appendLine("   - Prefer encrypted messaging (Signal, WhatsApp) over SMS")
                    appendLine("   - Be mindful of sensitive phone conversations")
                    appendLine("   - Note your location in case pattern develops")
                    appendLine()
                    appendLine("VERIFICATION:")
                    appendLine("   - Check cell ID in cellmapper.net database")
                    appendLine("   - If cell doesn't appear, suspicion increases")
                    appendLine("   - If cell appears with correct location, likely false positive")
                    appendLine()
                    appendLine("WATCH FOR:")
                    appendLine("   - Additional anomalies in same area = threat increases")
                    appendLine("   - Same anomaly in different areas = possible targeted surveillance")
                    appendLine("   - Anomaly only at specific times/events = pattern worth noting")
                }
                context.imsiCatcherScore >= 30 -> {
                    appendLine("*** LOW CONCERN - MONITOR SITUATION ***")
                    appendLine()
                    appendLine("ASSESSMENT:")
                    appendLine("   - This is likely normal network behavior")
                    appendLine("   - Cell networks frequently optimize and hand off")
                    appendLine("   - Single events are usually not concerning")
                    appendLine()
                    appendLine("WHAT TO WATCH FOR:")
                    appendLine("   - Repeated similar events = more concerning")
                    appendLine("   - Events combined with encryption downgrade = concerning")
                    appendLine("   - Pattern tied to specific locations/times = investigate")
                    appendLine()
                    appendLine("NO IMMEDIATE ACTION REQUIRED")
                    appendLine("   - Continue using your phone normally")
                    appendLine("   - The app is logging this for pattern analysis")
                    appendLine("   - You'll be alerted if threat level increases")
                }
                else -> {
                    appendLine("*** INFORMATIONAL - NORMAL BEHAVIOR ***")
                    appendLine()
                    appendLine("This event was logged but is not concerning:")
                    appendLine("   - Minor network event that is common")
                    appendLine("   - By itself, this indicates nothing suspicious")
                    appendLine("   - Recorded for pattern analysis only")
                    appendLine()
                    appendLine("Your phone is operating normally.")
                    appendLine("No action needed.")
                }
            }
        }
    }

    /**
     * Generate a brief summary for notifications (shorter than full AI prompt).
     */
    fun generateNotificationSummary(context: CellularDetectionContext, detection: Detection): String {
        return buildString {
            append("IMSI Catcher Detection: ${context.imsiCatcherScore}% likelihood. ")

            when {
                context.imsiCatcherScore >= 90 -> {
                    append("CRITICAL - Enable airplane mode immediately. ")
                }
                context.imsiCatcherScore >= 70 -> {
                    append("HIGH - Avoid sensitive calls/SMS. Use encrypted apps. ")
                }
                context.imsiCatcherScore >= 50 -> {
                    append("MODERATE - Use caution with communications. ")
                }
                else -> {
                    append("LOW - Monitoring for patterns. ")
                }
            }

            // Add key indicator
            when (context.anomalyType) {
                CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE ->
                    append("Your connection was downgraded to weak encryption (2G).")
                CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK ->
                    append("Connected to test/invalid network ID.")
                CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE ->
                    append("Cell tower changed while you were stationary.")
                else ->
                    append("Anomaly: ${context.anomalyType.name}")
            }
        }
    }
}

/**
 * Interface for cellular detection handlers.
 *
 * This interface follows the pattern from SatelliteDetectionHandler for
 * anomaly-based detection processing.
 */
interface CellularDetectionHandlerInterface<T> {
    /**
     * Check if this handler can process the given context.
     */
    fun canHandle(context: T): Boolean

    /**
     * Process the context and return a Detection if warranted.
     * Returns null if the anomaly doesn't meet thresholds or is disabled.
     */
    suspend fun handle(context: T): Detection?

    /**
     * Generate an AI analysis prompt for the detection.
     */
    fun generateAiPrompt(context: T, detection: Detection): String
}

/**
 * Cellular-specific detection context containing all relevant cell data.
 */
data class CellularDetectionContext(
    // Anomaly info
    val anomalyType: CellularMonitor.AnomalyType,

    // Network identifiers
    val mcc: String?,
    val mnc: String?,
    val cellId: Long?,
    val previousCellId: Long?,
    val lac: Int?,  // Location Area Code (2G/3G)
    val tac: Int?,  // Tracking Area Code (4G/5G)

    // Signal info
    val signalStrength: Int,
    val previousSignalStrength: Int?,

    // Network type
    val networkType: String,
    val previousNetworkType: String?,
    val encryptionType: EncryptionType,

    // Roaming status
    val isRoaming: Boolean,

    // Location
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,

    // IMSI catcher analysis
    val imsiCatcherScore: Int,
    val contributingFactors: List<String> = emptyList(),

    // Confidence and severity from CellularMonitor
    val confidence: CellularMonitor.AnomalyConfidence? = null,
    val severity: ThreatLevel? = null,

    // Movement analysis (optional, enriched)
    val movementType: MovementType? = null,
    val speedKmh: Double? = null,
    val distanceMeters: Double? = null,

    // Cell trust analysis (optional, enriched)
    val cellTrustScore: Int? = null,
    val cellSeenCount: Int? = null,
    val isInFamiliarArea: Boolean? = null,

    // Encryption downgrade chain
    val encryptionDowngradeChain: List<String>? = null
)

/**
 * Encryption strength classification for cellular networks.
 */
enum class EncryptionType(val displayName: String, val description: String) {
    STRONG_5G_LTE("Strong (5G/LTE)", "AES-256 encryption"),
    MODERATE_3G("Moderate (3G)", "KASUMI cipher - known weaknesses"),
    WEAK_2G("Weak (2G)", "A5/1 cipher - crackable in real-time"),
    NONE("None", "No encryption - plaintext"),
    UNKNOWN("Unknown", "Encryption status unknown")
}

/**
 * Movement type classification based on speed.
 */
enum class MovementType(val displayName: String, val maxSpeedKmh: Double) {
    STATIONARY("Stationary", 1.0),
    WALKING("Walking", 7.0),
    RUNNING("Running", 20.0),
    CYCLING("Cycling", 40.0),
    VEHICLE("Vehicle", 150.0),
    HIGH_SPEED_VEHICLE("High-Speed Vehicle", 350.0),
    IMPOSSIBLE("Impossible/Teleport", Double.MAX_VALUE);

    companion object {
        fun fromSpeed(speedKmh: Double): MovementType {
            return when {
                speedKmh < STATIONARY.maxSpeedKmh -> STATIONARY
                speedKmh < WALKING.maxSpeedKmh -> WALKING
                speedKmh < RUNNING.maxSpeedKmh -> RUNNING
                speedKmh < CYCLING.maxSpeedKmh -> CYCLING
                speedKmh < VEHICLE.maxSpeedKmh -> VEHICLE
                speedKmh < HIGH_SPEED_VEHICLE.maxSpeedKmh -> HIGH_SPEED_VEHICLE
                else -> IMPOSSIBLE
            }
        }
    }
}

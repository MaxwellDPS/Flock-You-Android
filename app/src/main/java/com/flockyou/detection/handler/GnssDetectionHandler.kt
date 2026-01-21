package com.flockyou.detection.handler

import android.util.Log
import com.flockyou.ai.EnrichedDetectorData
import com.flockyou.ai.PromptTemplates
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.ThreatLevel
import com.flockyou.monitoring.GnssSatelliteMonitor
import com.flockyou.monitoring.GnssSatelliteMonitor.ConstellationType
import com.flockyou.monitoring.GnssSatelliteMonitor.DriftTrend
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyAnalysis
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyType
import com.flockyou.monitoring.GnssSatelliteMonitor.AnomalyConfidence
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detection handler for GNSS (GPS/satellite) spoofing and jamming attacks.
 *
 * Handles all GNSS-related detection methods:
 * - GNSS_SPOOFING: Fake satellite signals attempting to manipulate position
 * - GNSS_JAMMING: Signal blocking/degradation attacks
 * - GNSS_SIGNAL_ANOMALY: Unusual signal characteristics (too uniform, abnormal levels)
 * - GNSS_GEOMETRY_ANOMALY: Impossible satellite positions
 * - GNSS_SIGNAL_LOSS: Sudden loss of satellite signals
 * - GNSS_CLOCK_ANOMALY: Timing discontinuities suggesting manipulation
 * - GNSS_MULTIPATH: Severe reflection interference (urban canyons, indoor)
 * - GNSS_CONSTELLATION_ANOMALY: Unexpected constellation behavior
 *
 * Performs:
 * - Constellation fingerprinting (GPS/GLONASS/Galileo/BeiDou/QZSS/SBAS/IRNSS)
 * - C/N0 baseline deviation tracking
 * - Clock drift accumulation analysis
 * - Satellite geometry validation
 * - Spoofing/jamming likelihood calculation
 *
 * ===================================================================================
 * REAL-WORLD GNSS CONFIRMATION AND VALIDATION METHODS
 * ===================================================================================
 *
 * When GNSS anomalies are detected, these cross-validation methods help confirm
 * whether the anomaly is a genuine attack or environmental/equipment issue:
 *
 * CONFIRMATION METHOD 1: Cell Tower Triangulation Comparison
 * ----------------------------------------------------------
 * - Compare GPS-reported position with cell tower-derived position
 * - Cellular position accuracy: 50-300m in urban, 1-10km in rural
 * - If GPS says you're in Moscow but cell towers say Los Angeles = SPOOFING
 * - Android: Use FusedLocationProvider with PRIORITY_BALANCED_POWER_ACCURACY
 *
 * CONFIRMATION METHOD 2: WiFi Positioning System (WPS) Comparison
 * ---------------------------------------------------------------
 * - Compare GPS position with WiFi-based geolocation
 * - WiFi positioning accuracy: 15-40m where WiFi APs are mapped
 * - Spoofing GPS doesn't affect WiFi positioning databases
 * - Discrepancy > 1km in urban area = highly suspicious
 *
 * CONFIRMATION METHOD 3: Accelerometer/IMU Consistency
 * ----------------------------------------------------
 * - Compare GPS-derived velocity with accelerometer-integrated velocity
 * - If GPS shows 100 km/h but accelerometer shows stationary = SPOOFING
 * - Check for "teleportation" - position jumps that violate physics
 * - Maximum human running: ~12 m/s, vehicle: depends on context
 *
 * CONFIRMATION METHOD 4: Altitude Sanity Check
 * --------------------------------------------
 * - Spoofing often gets altitude WRONG (harder to fake correctly)
 * - Compare GPS altitude with:
 *   - Barometric altimeter (if available)
 *   - Known terrain elevation at position (DEM lookup)
 *   - Recent altitude history (sudden 1000m jump = suspicious)
 *
 * CONFIRMATION METHOD 5: Time Source Comparison
 * ---------------------------------------------
 * - Compare GPS time with NTP server time
 * - GPS provides time accurate to ~100ns, NTP to ~10ms
 * - If GPS time differs from NTP by > 1 second = SUSPICIOUS
 * - Meaconing attacks shift time proportionally to position error
 * - Formula: position_error_meters = time_error_seconds * 299,792,458
 *
 * CONFIRMATION METHOD 6: Multi-Device Comparison
 * ----------------------------------------------
 * - Ask users nearby if they see the same anomaly
 * - If only YOUR device is affected = personal device issue or targeted attack
 * - If multiple devices affected = regional spoofing/jamming operation
 * - Fleet tracking systems can compare across vehicles
 *
 * CONFIRMATION METHOD 7: Galileo OSNMA Authentication
 * ---------------------------------------------------
 * - Galileo (EU) has Open Service Navigation Message Authentication since 2023
 * - Provides cryptographic proof that navigation message is genuine
 * - Spoofing detection: authentication failure = CONFIRMED spoofing
 * - Requires compatible receiver and processing capability
 *
 * USER CONFIRMATION STEPS (suggest to user):
 * ------------------------------------------
 * 1. "Compare your GPS location to what your carrier shows for E911"
 *    - Call carrier customer service or use carrier app
 *    - E911 location uses cell towers, not GPS
 *
 * 2. "Check if Google Maps traffic layer loads correctly for your position"
 *    - Traffic data is server-side, based on actual road network
 *    - If traffic shows for wrong area, GPS is being spoofed
 *
 * 3. "Ask someone nearby if their GPS shows the same anomaly"
 *    - Single-device issue vs regional attack
 *    - Compare across different phone brands/models
 *
 * 4. "Try airplane mode for 30 seconds, then re-acquire GPS"
 *    - Forces GPS cold start, may break spoofer lock
 *    - Note if position suddenly "jumps" when re-acquiring
 *
 * 5. "Check if your device time drifted significantly"
 *    - Open Settings > Date & Time
 *    - If time is wrong by minutes/hours after GPS use = meaconing attack
 *
 * 6. "Move 100+ meters and check if position updates correctly"
 *    - Spoofing may create "sticky" position that doesn't update
 *    - Or may track your movement incorrectly
 *
 * 7. "Check position against visual landmarks"
 *    - Can you see the building/intersection GPS claims you're at?
 *    - Kremlin spoofing victims saw they were NOT at Vnukovo Airport
 * ===================================================================================
 */
@Singleton
class GnssDetectionHandler @Inject constructor() {

    companion object {
        private const val TAG = "GnssDetectionHandler"

        // Detection thresholds - calibrated to reduce false positives
        private const val SPOOFING_HIGH_THRESHOLD = 70f
        private const val SPOOFING_MEDIUM_THRESHOLD = 40f
        private const val JAMMING_HIGH_THRESHOLD = 70f
        private const val JAMMING_MEDIUM_THRESHOLD = 40f

        // C/N0 thresholds - calibrated based on real-world GNSS behavior
        // Normal GNSS signals have variance of 0.5-5.0 due to different elevation angles,
        // atmospheric conditions, multipath, etc. Only extremely low variance is suspicious.
        private const val CN0_VARIANCE_SUSPICIOUS = 0.15  // dB-Hz - ONLY this low indicates spoofing
        private const val CN0_VARIANCE_WARNING = 0.5  // dB-Hz - low, but only flag with other indicators
        private const val CN0_DEVIATION_SIGNIFICANT = 3.0  // standard deviations from baseline

        // Geometry thresholds
        private const val GEOMETRY_POOR_THRESHOLD = 0.4f
        private const val LOW_ELEV_HIGH_SIGNAL_THRESHOLD = 2

        // Clock drift thresholds
        private const val DRIFT_JUMP_SUSPICIOUS = 3
        private const val DRIFT_CUMULATIVE_SUSPICIOUS_MS = 10  // milliseconds

        // Satellite count thresholds - for jamming/spoofing plausibility
        private const val GOOD_FIX_SATELLITES = 10
        private const val STRONG_FIX_SATELLITES = 30
        private const val JAMMING_MAX_SATELLITES = 8  // Can't claim jamming with more than this
    }

    // ==================== USER CONFIRMATION GUIDANCE ====================

    /**
     * User confirmation steps to suggest when GNSS anomaly is detected.
     * These help the user validate whether the anomaly is real.
     */
    data class UserConfirmationStep(
        val id: String,
        val title: String,
        val description: String,
        val howTo: String,
        val expectedOutcome: String,
        val spoofingIndicator: String,
        val difficulty: ConfirmationDifficulty
    )

    enum class ConfirmationDifficulty {
        EASY,      // Can do immediately with current device
        MODERATE,  // Requires simple action (airplane mode, walking)
        ADVANCED   // Requires external resources or technical knowledge
    }

    /**
     * Get confirmation steps appropriate for the detected anomaly type
     */
    fun getUserConfirmationSteps(detectionMethod: DetectionMethod, threatLevel: ThreatLevel): List<UserConfirmationStep> {
        val steps = mutableListOf<UserConfirmationStep>()

        // Always include basic steps for any GNSS anomaly
        steps.add(UserConfirmationStep(
            id = "VISUAL_CHECK",
            title = "Visual Landmark Check",
            description = "Compare GPS position with visible surroundings",
            howTo = "Open Google Maps and check if the blue dot matches where you actually are. Can you see the building/street the map shows?",
            expectedOutcome = "Position should match within ~20 meters of visible landmarks",
            spoofingIndicator = "If GPS shows you're at a different location than what you can see, this strongly suggests spoofing",
            difficulty = ConfirmationDifficulty.EASY
        ))

        steps.add(UserConfirmationStep(
            id = "TRAFFIC_CHECK",
            title = "Traffic Layer Verification",
            description = "Check if traffic data loads correctly for your GPS position",
            howTo = "In Google Maps, enable the Traffic layer. Check if traffic conditions shown make sense for your actual location.",
            expectedOutcome = "Traffic should match roads near your real position",
            spoofingIndicator = "If traffic shows for roads far away from you, or no traffic loads where there should be, GPS may be spoofed",
            difficulty = ConfirmationDifficulty.EASY
        ))

        steps.add(UserConfirmationStep(
            id = "AIRPLANE_RESET",
            title = "Airplane Mode Reset",
            description = "Force GPS cold start to break potential spoofer lock",
            howTo = "Enable Airplane Mode, wait 30 seconds, then disable it. Watch your GPS position as it re-acquires satellites.",
            expectedOutcome = "Position should gradually converge to your real location",
            spoofingIndicator = "If position 'jumps' dramatically when re-acquiring, or immediately returns to wrong location, spoofing likely continues",
            difficulty = ConfirmationDifficulty.MODERATE
        ))

        steps.add(UserConfirmationStep(
            id = "MOVEMENT_TEST",
            title = "Movement Tracking Test",
            description = "Walk 100+ meters and verify position updates correctly",
            howTo = "Start walking in a known direction while watching GPS. Walk at least 100 meters. Check if blue dot moves correctly.",
            expectedOutcome = "Position should track your movement direction and distance accurately",
            spoofingIndicator = "Position doesn't move, moves in wrong direction, or 'teleports' = spoofing",
            difficulty = ConfirmationDifficulty.MODERATE
        ))

        // Add spoofing-specific steps
        if (detectionMethod == DetectionMethod.GNSS_SPOOFING ||
            detectionMethod == DetectionMethod.GNSS_SIGNAL_ANOMALY) {

            steps.add(UserConfirmationStep(
                id = "MULTI_DEVICE",
                title = "Compare With Another Device",
                description = "Check if nearby devices show the same anomaly",
                howTo = "Ask someone nearby to check their GPS position. Compare locations - are both devices affected?",
                expectedOutcome = "If spoofing is regional, multiple devices will show wrong position. If only yours is affected, could be device-specific.",
                spoofingIndicator = "Multiple devices with same wrong position = regional spoofing attack (Kremlin-style)",
                difficulty = ConfirmationDifficulty.MODERATE
            ))

            steps.add(UserConfirmationStep(
                id = "TIME_CHECK",
                title = "Device Time Verification",
                description = "Check if GPS has affected your device time",
                howTo = "Go to Settings > Date & Time. Check if automatic time is enabled and if the displayed time is correct.",
                expectedOutcome = "Time should be accurate to within a few seconds",
                spoofingIndicator = "If time is off by minutes or hours, this indicates a meaconing (replay) attack",
                difficulty = ConfirmationDifficulty.EASY
            ))

            steps.add(UserConfirmationStep(
                id = "ALTITUDE_CHECK",
                title = "Altitude Sanity Check",
                description = "Verify reported altitude makes sense",
                howTo = "Check the altitude shown in a GPS app. Compare with known elevation of your location (sea level, hills, building floor).",
                expectedOutcome = "Altitude should be reasonable for your known location",
                spoofingIndicator = "Altitude wildly wrong (e.g., showing 1000m when you're at sea level) = spoofing (altitude is hard to fake correctly)",
                difficulty = ConfirmationDifficulty.MODERATE
            ))
        }

        // Add jamming-specific steps
        if (detectionMethod == DetectionMethod.GNSS_JAMMING ||
            detectionMethod == DetectionMethod.GNSS_SIGNAL_LOSS) {

            steps.add(UserConfirmationStep(
                id = "LOCATION_CHANGE",
                title = "Change Physical Location",
                description = "Move to an open area to test if signal improves",
                howTo = "If you're indoors, near buildings, or in a vehicle, move to an open outdoor area with clear sky view.",
                expectedOutcome = "Signal should improve significantly in open areas",
                spoofingIndicator = "If signal remains degraded even in open sky = potential active jamming nearby",
                difficulty = ConfirmationDifficulty.MODERATE
            ))

            steps.add(UserConfirmationStep(
                id = "CELL_WIFI_CHECK",
                title = "Alternative Positioning Check",
                description = "Check if cell/WiFi positioning still works",
                howTo = "Disable GPS and use WiFi/cell positioning only. Check if approximate location is correct.",
                expectedOutcome = "Cell/WiFi position should give rough but correct location",
                spoofingIndicator = "If only GPS is affected but cell/WiFi work = targeted GPS jamming",
                difficulty = ConfirmationDifficulty.ADVANCED
            ))
        }

        // Add high-threat specific steps
        if (threatLevel == ThreatLevel.HIGH || threatLevel == ThreatLevel.CRITICAL) {

            steps.add(UserConfirmationStep(
                id = "E911_CHECK",
                title = "Emergency Location Check",
                description = "Compare with carrier's E911 location",
                howTo = "Some carrier apps show E911 location. Alternatively, call your carrier and ask where their network shows you.",
                expectedOutcome = "E911 uses cell towers, not GPS - should show real approximate location",
                spoofingIndicator = "Large discrepancy between GPS and E911 = confirmed GPS spoofing",
                difficulty = ConfirmationDifficulty.ADVANCED
            ))

            steps.add(UserConfirmationStep(
                id = "DOCUMENT",
                title = "Document the Anomaly",
                description = "Screenshot and record details for potential report",
                howTo = "Take screenshots of the GPS anomaly, note the time, location, and circumstances. Save for potential report to authorities.",
                expectedOutcome = "Documentation for investigation",
                spoofingIndicator = "N/A - This step is for reporting, not detection",
                difficulty = ConfirmationDifficulty.EASY
            ))
        }

        // Sort by difficulty (easy first)
        return steps.sortedBy { it.difficulty.ordinal }
    }

    /**
     * Get real-world context explanation for the detected anomaly
     */
    fun getRealWorldContext(detectionMethod: DetectionMethod, context: GnssDetectionContext): RealWorldContext {
        return when (detectionMethod) {
            DetectionMethod.GNSS_SPOOFING -> getSpoofingContext(context)
            DetectionMethod.GNSS_JAMMING -> getJammingContext(context)
            DetectionMethod.GNSS_SIGNAL_ANOMALY -> getSignalAnomalyContext(context)
            DetectionMethod.GNSS_GEOMETRY_ANOMALY -> getGeometryAnomalyContext(context)
            DetectionMethod.GNSS_CLOCK_ANOMALY -> getClockAnomalyContext(context)
            DetectionMethod.GNSS_MULTIPATH -> getMultipathContext(context)
            DetectionMethod.GNSS_CONSTELLATION_ANOMALY -> getConstellationContext(context)
            else -> getGenericContext(context)
        }
    }

    data class RealWorldContext(
        val likelyScenario: String,
        val realWorldExamples: List<String>,
        val technicalExplanation: String,
        val falsePositiveConsiderations: List<String>,
        val recommendedActions: List<String>,
        val whatAttackerGains: String?
    )

    private fun getSpoofingContext(context: GnssDetectionContext): RealWorldContext {
        val likelyScenario = when {
            context.cn0Variance < CN0_VARIANCE_SUSPICIOUS -> "Uniform signal spoofing - likely single-transmitter attack"
            context.lowElevHighSignalCount > 2 -> "Low-elevation spoofing - signals claiming impossible positions"
            context.constellations.size == 1 -> "Single-constellation spoofing - attacker only targeting one system"
            else -> "Multi-indicator spoofing pattern detected"
        }

        return RealWorldContext(
            likelyScenario = likelyScenario,
            realWorldExamples = listOf(
                "Kremlin Circle (Moscow): Ships report being at Vnukovo Airport, 37km from actual location",
                "RQ-170 Capture (Iran, 2011): Drone allegedly captured using gradual position spoofing",
                "Shanghai Port: Documented GPS interference affecting commercial shipping",
                "Personal Privacy Devices: Truckers/employees hiding location from fleet tracking"
            ),
            technicalExplanation = buildString {
                append("GPS spoofing works by broadcasting fake satellite signals stronger than real ones. ")
                append("Your receiver locks onto the fake signals because they're stronger. ")
                append("Key signatures: ")
                if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS) {
                    append("All signals same strength (real satellites vary by 10-20 dB). ")
                }
                if (context.lowElevHighSignalCount > 0) {
                    append("Satellites at low elevation with impossibly high signal strength. ")
                }
                append("Spoofing multiple constellations (GPS+GLONASS+Galileo) simultaneously is very difficult.")
            },
            falsePositiveConsiderations = listOf(
                "Indoor environments can cause signal uniformity due to attenuation",
                "Some receivers have AGC that normalizes signal levels",
                "Multipath in urban canyons can create unusual signal patterns",
                "Device calibration issues can affect signal measurements"
            ),
            recommendedActions = listOf(
                "Compare GPS position with cell tower/WiFi positioning",
                "Check if position matches visible landmarks",
                "Try airplane mode reset to force satellite re-acquisition",
                "If multiple devices affected, report to authorities"
            ),
            whatAttackerGains = "Position control - can make you think you're somewhere else, affect navigation, or manipulate location-based services"
        )
    }

    private fun getJammingContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = when {
                context.satelliteCount < 4 -> "Active jamming - GPS signals being overpowered"
                context.jammingIndicator > 50 -> "Partial jamming - signal degradation detected"
                else -> "Potential jamming or severe interference"
            },
            realWorldExamples = listOf(
                "Personal Privacy Devices (PPDs): Cheap jammers used by truckers to avoid tracking",
                "Newark Airport (2013): Jammer caused FAA Ground Based Augmentation System outages",
                "Military Operations: Intentional jamming in conflict zones",
                "Criminal Activity: Jammers used during cargo theft to disable GPS trackers"
            ),
            technicalExplanation = buildString {
                append("GPS jamming works by broadcasting noise on GPS frequencies, drowning out the weak satellite signals. ")
                append("GPS signals from space are very weak (~-160 dBW at Earth's surface). ")
                append("A 1-watt jammer can affect receivers within several hundred meters. ")
                append("True jamming causes: complete signal loss, no position fix possible, all constellations affected.")
            },
            falsePositiveConsiderations = listOf(
                "Being indoors severely attenuates GPS signals (NOT jamming)",
                "Parking garages, tunnels, and underground areas block signals naturally",
                "Dense urban canyons can reduce satellite visibility",
                "Electromagnetic interference from nearby equipment"
            ),
            recommendedActions = listOf(
                "Move to an open outdoor area with clear sky view",
                "Check if other devices in same location are also affected",
                "If jamming persists outdoors, try moving 100+ meters",
                "Report suspected illegal jamming to authorities (FCC in US)"
            ),
            whatAttackerGains = "Denial of positioning - prevents GPS tracking, can disable fleet management, enables cargo theft without GPS alerts"
        )
    }

    private fun getSignalAnomalyContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = "Unusual signal characteristics detected - may be environmental or attack",
            realWorldExamples = listOf(
                "Signal uniformity is a key spoofing signature from single-transmitter attacks",
                "Abnormally high signals (>55 dB-Hz) suggest nearby terrestrial transmitter",
                "Sudden signal spikes during normal operation can indicate attack onset"
            ),
            technicalExplanation = buildString {
                append("Normal GNSS signals vary by 10-20 dB-Hz due to: ")
                append("satellite elevation angles (higher = stronger), ")
                append("atmospheric conditions, ")
                append("antenna gain patterns, ")
                append("multipath environment. ")
                append("Signal variance < 0.15 dB-Hz is highly suspicious - suggests single source.")
            },
            falsePositiveConsiderations = listOf(
                "Some receiver chips have AGC that compresses signal dynamic range",
                "Indoor weak signals may have reduced variance due to noise floor effects",
                "Recent cold start may show transient signal anomalies"
            ),
            recommendedActions = listOf(
                "Monitor signal characteristics over time",
                "Compare with other devices in same location",
                "Check if anomaly persists across multiple satellite acquisitions"
            ),
            whatAttackerGains = null  // May not be an attack
        )
    }

    private fun getGeometryAnomalyContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = "Satellite geometry is suspicious - positions physically implausible",
            realWorldExamples = listOf(
                "All satellites at same elevation = spoofing (real sky has varied elevations)",
                "Satellites clustered in one azimuth direction = unusual (should be spread)",
                "Low elevation satellites with high signal = physically impossible without nearby transmitter"
            ),
            technicalExplanation = buildString {
                append("Real satellite geometry is constrained by orbital mechanics. ")
                append("GPS satellites orbit at ~20,200 km altitude in 6 orbital planes. ")
                append("From any location, satellites should be spread across the sky at varying elevations. ")
                append("Low elevation satellites (< 5 degrees) are near the horizon and should have WEAK signals due to longer atmospheric path.")
            },
            falsePositiveConsiderations = listOf(
                "Urban canyons may block low-elevation satellites legitimately",
                "Mountains or terrain can affect satellite visibility",
                "Receiver antenna orientation can affect relative signal strengths"
            ),
            recommendedActions = listOf(
                "Compare satellite positions with online satellite position calculators",
                "Move to open area to verify full sky visibility",
                "Check if geometry anomaly persists over several minutes"
            ),
            whatAttackerGains = "Spoofed geometry indicates position manipulation attempt"
        )
    }

    private fun getClockAnomalyContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = when (context.driftTrend) {
                DriftTrend.ERRATIC -> "Erratic clock behavior - possible meaconing or replay attack"
                DriftTrend.INCREASING, DriftTrend.DECREASING -> "Steady clock drift - possible gradual spoofing"
                else -> "Clock discontinuity detected"
            },
            realWorldExamples = listOf(
                "Meaconing attacks replay real signals with time delay - causes proportional position error",
                "Position error (meters) = Time error (seconds) x 299,792,458 (speed of light)",
                "1 microsecond time error = ~300 meters position error"
            ),
            technicalExplanation = buildString {
                append("GPS provides both position AND time. ")
                append("Receiver clock is disciplined by GPS timing signals. ")
                append("Clock jumps or discontinuities can indicate: ")
                append("signal manipulation, ")
                append("receiver handoff issues, ")
                append("or meaconing attacks. ")
                append("Legitimate clock drift is typically < 100 nanoseconds between measurements.")
            },
            falsePositiveConsiderations = listOf(
                "Receiver clock resets during cold start are normal",
                "Some receivers have documented clock discontinuities during mode changes",
                "Temperature changes can affect oscillator stability"
            ),
            recommendedActions = listOf(
                "Compare device time with NTP server or known accurate time source",
                "Check if time error correlates with position error",
                "Monitor clock behavior over extended period"
            ),
            whatAttackerGains = "Time manipulation enables position spoofing through meaconing attacks"
        )
    }

    private fun getMultipathContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = "Severe multipath interference - likely environmental, rarely attack",
            realWorldExamples = listOf(
                "Urban canyons: Signals bounce off glass/steel buildings",
                "Near water: Strong specular reflections from water surface",
                "Indoor: Multiple reflections from walls, ceiling, floor",
                "Vehicles: Reflections from nearby metal surfaces"
            ),
            technicalExplanation = buildString {
                append("Multipath occurs when GPS signals reach the receiver via multiple paths ")
                append("(direct + reflections). This causes: ")
                append("signal interference, ")
                append("position errors typically 1-5 meters, ")
                append("increased measurement noise. ")
                append("Modern receivers have multipath mitigation, but severe cases still affect positioning.")
            },
            falsePositiveConsiderations = listOf(
                "Multipath is NORMAL in cities - not an attack",
                "Indoor multipath is expected - move outside for clear assessment",
                "Multipath with good position fix is not concerning",
                "Only report multipath if ALL constellations affected uniformly (unusual)"
            ),
            recommendedActions = listOf(
                "Move to open area away from buildings and reflective surfaces",
                "Wait for multipath to clear - often temporary",
                "If position fix is maintained, multipath is not critical"
            ),
            whatAttackerGains = null  // Multipath is almost never an attack
        )
    }

    private fun getConstellationContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = "Constellation behavior anomaly - may indicate selective interference",
            realWorldExamples = listOf(
                "Kremlin-style: GPS affected but GLONASS normal (Russian attackers don't spoof own system)",
                "GPS-only jammers: Cheap devices target only GPS L1 frequency",
                "Regional systems (BeiDou, QZSS, IRNSS) may be selectively targeted in conflicts"
            ),
            technicalExplanation = buildString {
                append("Multiple GNSS constellations provide redundancy. ")
                append("GPS (US), GLONASS (Russia), Galileo (EU), BeiDou (China). ")
                append("Attacking ALL constellations simultaneously is very difficult. ")
                append("If one constellation is anomalous while others are healthy, ")
                append("this is strong evidence of selective interference.")
            },
            falsePositiveConsiderations = listOf(
                "Some regions have limited visibility of certain constellations",
                "Receiver may not support all constellation types",
                "Orbital maintenance can temporarily reduce constellation availability",
                "QZSS and IRNSS are regional - not visible globally"
            ),
            recommendedActions = listOf(
                "Check which specific constellation is affected",
                "Compare constellation health across multiple devices",
                "If only GPS is affected with GLONASS normal, suspect targeted spoofing"
            ),
            whatAttackerGains = "Selective targeting suggests sophisticated attacker with specific goals"
        )
    }

    private fun getGenericContext(context: GnssDetectionContext): RealWorldContext {
        return RealWorldContext(
            likelyScenario = "GNSS anomaly detected - further analysis recommended",
            realWorldExamples = listOf(
                "Various GNSS attacks documented worldwide since 2011",
                "Personal privacy devices (jammers) are common illegal interference source",
                "State-level spoofing documented around protected facilities"
            ),
            technicalExplanation = "GNSS anomalies can result from attacks, environmental factors, or equipment issues. Cross-validation with other positioning sources is recommended.",
            falsePositiveConsiderations = listOf(
                "Environmental factors (buildings, weather) cause many anomalies",
                "Equipment malfunction can mimic attack signatures",
                "Temporary satellite maintenance affects availability"
            ),
            recommendedActions = listOf(
                "Follow user confirmation steps to validate anomaly",
                "Compare with alternative positioning sources",
                "Monitor for pattern persistence"
            ),
            whatAttackerGains = null
        )
    }

    /**
     * Supported detection methods for GNSS handler
     */
    val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.GNSS_SPOOFING,
        DetectionMethod.GNSS_JAMMING,
        DetectionMethod.GNSS_SIGNAL_ANOMALY,
        DetectionMethod.GNSS_GEOMETRY_ANOMALY,
        DetectionMethod.GNSS_SIGNAL_LOSS,
        DetectionMethod.GNSS_CLOCK_ANOMALY,
        DetectionMethod.GNSS_MULTIPATH,
        DetectionMethod.GNSS_CONSTELLATION_ANOMALY
    )

    /**
     * Supported device types for GNSS handler
     */
    fun getSupportedDeviceTypes(): Set<DeviceType> = setOf(
        DeviceType.GNSS_SPOOFER,
        DeviceType.GNSS_JAMMER
    )

    /**
     * Protocol for this handler
     */
    fun getProtocol(): DetectionProtocol = DetectionProtocol.GNSS

    /**
     * Check if this handler can process the given detection method
     */
    fun canHandle(method: DetectionMethod): Boolean {
        return method in supportedMethods
    }

    /**
     * Check if this handler can process the given device type
     */
    fun canHandleDeviceType(deviceType: DeviceType): Boolean {
        return deviceType in getSupportedDeviceTypes()
    }

    /**
     * Handle a GNSS detection context and produce a Detection object.
     * Returns null if the detection should be filtered out.
     */
    fun handleDetection(context: GnssDetectionContext): Detection? {
        Log.d(TAG, "Handling GNSS detection: method=${context.detectionMethod}, " +
            "spoofing=${context.spoofingLikelihood}%, jamming=${context.jammingLikelihood}%")

        val detectionMethod = determineDetectionMethod(context)
        val deviceType = determineDeviceType(context)
        val threatLevel = calculateThreatLevel(context)
        val threatScore = calculateThreatScore(context)

        return Detection(
            id = context.id ?: UUID.randomUUID().toString(),
            timestamp = context.timestamp,
            protocol = DetectionProtocol.GNSS,
            detectionMethod = detectionMethod,
            deviceType = deviceType,
            deviceName = buildDeviceName(context, detectionMethod),
            macAddress = null,  // GNSS doesn't use MAC addresses
            ssid = null,
            rssi = context.avgCn0.toInt(),  // Use C/N0 as signal indicator
            signalStrength = cn0ToSignalStrength(context.avgCn0),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = threatLevel,
            threatScore = threatScore,
            manufacturer = buildConstellationFingerprint(context.constellations),
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = buildMatchedPatterns(context),
            rawData = buildRawData(context),
            isActive = true,
            seenCount = 1,
            lastSeenTimestamp = context.timestamp
        )
    }

    /**
     * Handle a GNSS anomaly from the monitor and convert to Detection
     */
    fun handleAnomaly(
        anomaly: GnssAnomaly,
        analysis: GnssAnomalyAnalysis?
    ): Detection? {
        val context = GnssDetectionContext(
            timestamp = anomaly.timestamp,
            constellations = anomaly.affectedConstellations.toSet(),
            satelliteCount = 0,  // Not available from anomaly
            avgCn0 = analysis?.currentCn0Mean ?: 0.0,
            cn0Deviation = analysis?.cn0DeviationSigmas ?: 0.0,
            cn0Variance = analysis?.cn0Variance ?: 0.0,
            clockDrift = analysis?.cumulativeDriftNs ?: 0L,
            driftTrend = analysis?.driftTrend ?: DriftTrend.STABLE,
            driftJumpCount = analysis?.driftJumpCount ?: 0,
            geometryScore = analysis?.geometryScore ?: 0f,
            elevationDistribution = analysis?.elevationDistribution ?: "Unknown",
            azimuthCoverage = analysis?.azimuthCoverage ?: 0f,
            lowElevHighSignalCount = analysis?.lowElevHighSignalCount ?: 0,
            jammingIndicator = analysis?.jammingLikelihood ?: 0f,
            spoofingLikelihood = analysis?.spoofingLikelihood ?: 0f,
            jammingLikelihood = analysis?.jammingLikelihood ?: 0f,
            spoofingIndicators = analysis?.spoofingIndicators ?: emptyList(),
            jammingIndicators = analysis?.jammingIndicators ?: emptyList(),
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            detectionMethod = anomalyTypeToMethod(anomaly.type),
            rawAnomaly = anomaly,
            rawAnalysis = analysis
        )

        return handleDetection(context)
    }

    /**
     * Build an AI prompt for GNSS detection analysis
     */
    fun generateAiPrompt(context: GnssDetectionContext): String {
        val analysis = context.rawAnalysis
        if (analysis != null) {
            // Use the enriched prompt if we have full analysis data
            val detection = handleDetection(context)
            if (detection != null) {
                return PromptTemplates.buildGnssEnrichedPrompt(detection, analysis)
            }
        }

        // Build a basic prompt without full analysis
        return buildBasicGnssPrompt(context)
    }

    /**
     * Get enriched detector data for AI analysis
     */
    fun getEnrichedData(context: GnssDetectionContext): EnrichedDetectorData? {
        return context.rawAnalysis?.let { EnrichedDetectorData.Gnss(it) }
    }

    /**
     * Calculate spoofing likelihood percentage based on context
     *
     * IMPORTANT: Strong satellite fix (many satellites, multiple constellations)
     * is strong evidence AGAINST spoofing. Spoofing is very difficult to do
     * perfectly across multiple constellations.
     */
    fun calculateSpoofingLikelihood(context: GnssDetectionContext): Float {
        var score = 0f

        // Signal uniformity - only flag if EXTREMELY uniform (< 0.15)
        // Normal variance is 0.5-5.0, so 0.53 (from debug data) should NOT be flagged
        if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS && context.satelliteCount >= 4) {
            score += 25f
        } else if (context.cn0Variance < CN0_VARIANCE_WARNING && context.satelliteCount >= 4) {
            // Low variance but not extremely low - only minor contribution
            score += 5f
        }

        // Low elevation with high signal - strong spoofing indicator
        if (context.lowElevHighSignalCount > 2) {
            score += context.lowElevHighSignalCount * 10f
        }

        // Poor geometry
        if (context.geometryScore < GEOMETRY_POOR_THRESHOLD) {
            score += 15f
        }

        // Constellation anomalies
        if (context.constellations.size == 1 && context.satelliteCount >= 6) {
            score += 10f  // Single constellation unusual
        }

        // Clock drift anomalies
        if (context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS) {
            score += 15f
        }
        if (context.driftTrend == DriftTrend.ERRATIC) {
            score += 10f
        }

        // C/N0 deviation from baseline
        if (context.cn0Deviation > CN0_DEVIATION_SIGNIFICANT) {
            score += 10f
        }

        // Use existing spoofing indicators (but filter out uniformity-related ones if already counted)
        val uniqueIndicators = context.spoofingIndicators.filter {
            !it.contains("uniformity", ignoreCase = true) || context.cn0Variance < CN0_VARIANCE_SUSPICIOUS
        }
        score += uniqueIndicators.size * 5f

        // CRITICAL: Strong satellite fix reduces spoofing likelihood
        // Spoofing 30+ satellites across multiple constellations is extremely difficult
        if (context.satelliteCount >= STRONG_FIX_SATELLITES) {
            score *= 0.3f  // 70% reduction for very strong fix
        } else if (context.satelliteCount >= GOOD_FIX_SATELLITES) {
            score *= 0.6f  // 40% reduction for good fix
        }

        // Multiple constellations also reduce spoofing likelihood
        if (context.constellations.size >= 4) {
            score *= 0.7f  // 30% reduction for 4+ constellations
        } else if (context.constellations.size >= 3) {
            score *= 0.85f  // 15% reduction for 3 constellations
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Calculate jamming likelihood percentage based on context
     *
     * CRITICAL: Jamming is INCOMPATIBLE with:
     * - Having many visible satellites (30+)
     * - Having a good position fix (10+ satellites used)
     * - Good signal strength across satellites
     *
     * True jamming would prevent satellite acquisition entirely.
     * 70% jamming likelihood with 32 satellites used is IMPOSSIBLE.
     */
    fun calculateJammingLikelihood(context: GnssDetectionContext): Float {
        // CRITICAL: If we have many satellites with good signals, jamming is IMPOSSIBLE
        if (context.satelliteCount > JAMMING_MAX_SATELLITES) {
            // Cannot claim jamming with 8+ satellites visible
            return 0f
        }

        if (context.satelliteCount >= GOOD_FIX_SATELLITES) {
            // Cannot claim jamming with 10+ satellites
            return 0f
        }

        if (context.avgCn0 > 30.0 && context.satelliteCount >= 4) {
            // Cannot claim jamming with good fix and good signal strength
            return 0f
        }

        var score = 0f

        // Direct jamming indicator - but only if satellites are actually affected
        if (context.jammingIndicator > 50 && context.satelliteCount < 4) {
            score += 40f
        } else if (context.jammingIndicator > 25 && context.satelliteCount < GOOD_FIX_SATELLITES) {
            score += 20f
        }

        // Signal loss indicators - main jamming indicator
        if (context.satelliteCount < 4) {
            score += 30f
        } else if (context.satelliteCount < GOOD_FIX_SATELLITES) {
            score += 10f
        }

        // C/N0 significantly below baseline - but only with satellite loss
        if (context.cn0Deviation < -CN0_DEVIATION_SIGNIFICANT && context.satelliteCount < GOOD_FIX_SATELLITES) {
            score += 25f
        }

        // Use existing jamming indicators
        score += context.jammingIndicators.size * 10f

        // Final sanity check - reduce score if we still have reasonable satellite visibility
        if (context.satelliteCount >= 4) {
            score *= 0.5f  // Halve the score if we still have 4+ satellites
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Perform constellation fingerprinting
     */
    fun fingerprintConstellations(context: GnssDetectionContext): ConstellationFingerprint {
        val observed = context.constellations
        val expected = getExpectedConstellations(context.latitude, context.longitude)

        val missing = expected - observed
        val unexpected = observed - expected - setOf(ConstellationType.UNKNOWN)

        val matchScore = if (expected.isNotEmpty()) {
            ((observed.intersect(expected).size.toFloat() / expected.size) * 100).toInt()
        } else 100

        return ConstellationFingerprint(
            observed = observed,
            expected = expected,
            missing = missing,
            unexpected = unexpected,
            matchScore = matchScore,
            isSuspicious = matchScore < 50 || unexpected.isNotEmpty()
        )
    }

    // ==================== Private Helper Methods ====================

    private fun determineDetectionMethod(context: GnssDetectionContext): DetectionMethod {
        // Use explicit method if provided
        context.detectionMethod?.let { return it }

        // Otherwise determine from context
        val spoofing = context.spoofingLikelihood
        val jamming = context.jammingLikelihood

        return when {
            spoofing >= SPOOFING_HIGH_THRESHOLD -> DetectionMethod.GNSS_SPOOFING
            jamming >= JAMMING_HIGH_THRESHOLD -> DetectionMethod.GNSS_JAMMING
            context.lowElevHighSignalCount > LOW_ELEV_HIGH_SIGNAL_THRESHOLD -> DetectionMethod.GNSS_GEOMETRY_ANOMALY
            context.driftTrend == DriftTrend.ERRATIC || context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS -> DetectionMethod.GNSS_CLOCK_ANOMALY
            context.cn0Variance < CN0_VARIANCE_SUSPICIOUS -> DetectionMethod.GNSS_SIGNAL_ANOMALY
            context.satelliteCount < 4 && jamming > JAMMING_MEDIUM_THRESHOLD -> DetectionMethod.GNSS_SIGNAL_LOSS
            context.constellations.size == 1 && context.satelliteCount >= 6 -> DetectionMethod.GNSS_CONSTELLATION_ANOMALY
            spoofing >= SPOOFING_MEDIUM_THRESHOLD -> DetectionMethod.GNSS_SPOOFING
            jamming >= JAMMING_MEDIUM_THRESHOLD -> DetectionMethod.GNSS_JAMMING
            else -> DetectionMethod.GNSS_SIGNAL_ANOMALY
        }
    }

    private fun determineDeviceType(context: GnssDetectionContext): DeviceType {
        val spoofing = context.spoofingLikelihood
        val jamming = context.jammingLikelihood

        return when {
            jamming > spoofing && jamming >= JAMMING_MEDIUM_THRESHOLD -> DeviceType.GNSS_JAMMER
            spoofing >= SPOOFING_MEDIUM_THRESHOLD -> DeviceType.GNSS_SPOOFER
            context.satelliteCount < 4 -> DeviceType.GNSS_JAMMER  // Signal loss suggests jamming
            else -> DeviceType.GNSS_SPOOFER  // Default to spoofer for other anomalies
        }
    }

    private fun calculateThreatLevel(context: GnssDetectionContext): ThreatLevel {
        val spoofing = context.spoofingLikelihood
        val jamming = context.jammingLikelihood
        val maxLikelihood = maxOf(spoofing, jamming)

        return when {
            maxLikelihood >= 80 -> ThreatLevel.CRITICAL
            maxLikelihood >= 60 -> ThreatLevel.HIGH
            maxLikelihood >= 40 -> ThreatLevel.MEDIUM
            maxLikelihood >= 20 -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }
    }

    private fun calculateThreatScore(context: GnssDetectionContext): Int {
        var score = 0

        // Base score from likelihood
        score += (context.spoofingLikelihood * 0.4f).toInt()
        score += (context.jammingLikelihood * 0.4f).toInt()

        // Geometry issues
        if (context.geometryScore < GEOMETRY_POOR_THRESHOLD) {
            score += 10
        }
        if (context.lowElevHighSignalCount > 0) {
            score += context.lowElevHighSignalCount * 5
        }

        // Clock anomalies
        if (context.driftTrend == DriftTrend.ERRATIC) {
            score += 10
        }
        if (context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS) {
            score += 5
        }

        // Signal anomalies
        if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS && context.satelliteCount >= 4) {
            score += 10
        }

        return score.coerceIn(0, 100)
    }

    private fun buildDeviceName(context: GnssDetectionContext, method: DetectionMethod): String {
        val emoji = when (method) {
            DetectionMethod.GNSS_SPOOFING -> "\uD83C\uDFAF"  // Target emoji
            DetectionMethod.GNSS_JAMMING -> "\uD83D\uDCF5"  // No mobile phones emoji
            DetectionMethod.GNSS_SIGNAL_ANOMALY -> "\uD83D\uDCC8"  // Chart emoji
            DetectionMethod.GNSS_GEOMETRY_ANOMALY -> "\uD83D\uDEF0\uFE0F"  // Satellite emoji
            DetectionMethod.GNSS_SIGNAL_LOSS -> "\uD83D\uDCC9"  // Chart decreasing emoji
            DetectionMethod.GNSS_CLOCK_ANOMALY -> "\u23F0"  // Alarm clock emoji
            DetectionMethod.GNSS_MULTIPATH -> "\uD83D\uDD00"  // Shuffle emoji
            DetectionMethod.GNSS_CONSTELLATION_ANOMALY -> "\u274C"  // Cross mark emoji
            else -> "\uD83D\uDEF0\uFE0F"  // Default satellite emoji
        }

        val likelihood = maxOf(context.spoofingLikelihood, context.jammingLikelihood)
        return "$emoji ${method.displayName} (${String.format("%.0f", likelihood)}%)"
    }

    private fun buildConstellationFingerprint(constellations: Set<ConstellationType>): String {
        return constellations
            .filter { it != ConstellationType.UNKNOWN }
            .sortedBy { it.ordinal }
            .joinToString(", ") { it.displayName }
            .ifEmpty { "Unknown" }
    }

    private fun buildMatchedPatterns(context: GnssDetectionContext): String {
        val patterns = mutableListOf<String>()

        // Add spoofing indicators (filter out uniformity if variance is normal)
        val filteredSpoofingIndicators = context.spoofingIndicators.filter {
            !it.contains("uniformity", ignoreCase = true) || context.cn0Variance < CN0_VARIANCE_SUSPICIOUS
        }
        patterns.addAll(filteredSpoofingIndicators)

        // Add jamming indicators (only if satellite count supports jamming claim)
        if (context.satelliteCount <= JAMMING_MAX_SATELLITES) {
            patterns.addAll(context.jammingIndicators)
        }

        // Add geometry issues
        if (context.geometryScore < GEOMETRY_POOR_THRESHOLD) {
            patterns.add("Poor satellite geometry (${String.format("%.0f", context.geometryScore * 100)}%)")
        }
        if (context.lowElevHighSignalCount > 2) {
            patterns.add("${context.lowElevHighSignalCount} low-elevation high-signal satellites (spoofing indicator)")
        }

        // Add clock drift issues
        if (context.driftTrend == DriftTrend.ERRATIC) {
            patterns.add("Clock drift: ${context.driftTrend.displayName}")
        }
        if (context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS) {
            patterns.add("${context.driftJumpCount} clock drift jumps")
        }

        // Add C/N0 issues - ONLY if variance is EXTREMELY low (< 0.15)
        // Normal variance is 0.5-5.0, so 0.53 should NOT be flagged
        if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS && context.satelliteCount >= 4) {
            patterns.add("Signal uniformity extremely suspicious (variance: ${String.format("%.3f", context.cn0Variance)})")
        }
        if (abs(context.cn0Deviation) > CN0_DEVIATION_SIGNIFICANT) {
            patterns.add("C/N0 ${String.format("%.1f", context.cn0Deviation)} sigma from baseline")
        }

        // Add positive indicators (evidence against spoofing/jamming)
        if (context.satelliteCount >= STRONG_FIX_SATELLITES && context.constellations.size >= 3) {
            patterns.add("Strong multi-constellation fix (${context.satelliteCount} satellites, ${context.constellations.size} constellations)")
        }

        return patterns.joinToString(", ")
    }

    private fun buildRawData(context: GnssDetectionContext): String {
        return buildString {
            appendLine("=== GNSS Detection Context ===")
            appendLine("Satellites: ${context.satelliteCount}")
            appendLine("Constellations: ${context.constellations.joinToString { it.code }}")
            appendLine("Avg C/N0: ${String.format("%.1f", context.avgCn0)} dB-Hz")
            appendLine("C/N0 Deviation: ${String.format("%.2f", context.cn0Deviation)} sigma")
            appendLine("C/N0 Variance: ${String.format("%.2f", context.cn0Variance)}")
            appendLine("Geometry Score: ${String.format("%.0f", context.geometryScore * 100)}%")
            appendLine("Elevation Dist: ${context.elevationDistribution}")
            appendLine("Azimuth Coverage: ${String.format("%.0f", context.azimuthCoverage)}%")
            appendLine("Low-Elev High-Signal: ${context.lowElevHighSignalCount}")
            appendLine("Clock Drift: ${context.clockDrift / 1_000_000} ms")
            appendLine("Drift Trend: ${context.driftTrend.displayName}")
            appendLine("Drift Jumps: ${context.driftJumpCount}")
            appendLine("Spoofing Likelihood: ${String.format("%.0f", context.spoofingLikelihood)}%")
            appendLine("Jamming Likelihood: ${String.format("%.0f", context.jammingLikelihood)}%")
        }
    }

    private fun cn0ToSignalStrength(cn0: Double): SignalStrength {
        return when {
            cn0 > 45 -> SignalStrength.EXCELLENT
            cn0 > 35 -> SignalStrength.GOOD
            cn0 > 25 -> SignalStrength.MEDIUM
            cn0 > 15 -> SignalStrength.WEAK
            cn0 > 0 -> SignalStrength.VERY_WEAK
            else -> SignalStrength.UNKNOWN
        }
    }

    private fun anomalyTypeToMethod(type: GnssAnomalyType): DetectionMethod {
        return when (type) {
            GnssAnomalyType.SPOOFING_DETECTED -> DetectionMethod.GNSS_SPOOFING
            GnssAnomalyType.JAMMING_DETECTED -> DetectionMethod.GNSS_JAMMING
            GnssAnomalyType.SIGNAL_UNIFORMITY -> DetectionMethod.GNSS_SIGNAL_ANOMALY
            GnssAnomalyType.IMPOSSIBLE_GEOMETRY -> DetectionMethod.GNSS_GEOMETRY_ANOMALY
            GnssAnomalyType.SUDDEN_SIGNAL_LOSS -> DetectionMethod.GNSS_SIGNAL_LOSS
            GnssAnomalyType.CLOCK_ANOMALY -> DetectionMethod.GNSS_CLOCK_ANOMALY
            GnssAnomalyType.MULTIPATH_SEVERE -> DetectionMethod.GNSS_MULTIPATH
            GnssAnomalyType.CONSTELLATION_DROPOUT -> DetectionMethod.GNSS_CONSTELLATION_ANOMALY
            GnssAnomalyType.CN0_SPIKE -> DetectionMethod.GNSS_SIGNAL_ANOMALY
            GnssAnomalyType.ELEVATION_ANOMALY -> DetectionMethod.GNSS_GEOMETRY_ANOMALY
        }
    }

    private fun getExpectedConstellations(lat: Double?, lon: Double?): Set<ConstellationType> {
        // GPS and GLONASS are globally available
        val expected = mutableSetOf(
            ConstellationType.GPS,
            ConstellationType.GLONASS,
            ConstellationType.GALILEO  // EU system, globally available
        )

        if (lat != null && lon != null) {
            // BeiDou has better coverage in Asia-Pacific
            if (lon > 70 && lon < 180) {
                expected.add(ConstellationType.BEIDOU)
            }
            // QZSS primarily covers Japan and surrounding area
            if (lat > 20 && lat < 50 && lon > 120 && lon < 150) {
                expected.add(ConstellationType.QZSS)
            }
            // NavIC/IRNSS covers India
            if (lat > 0 && lat < 40 && lon > 60 && lon < 100) {
                expected.add(ConstellationType.IRNSS)
            }
        }

        // SBAS is generally expected in most regions
        expected.add(ConstellationType.SBAS)

        return expected
    }

    private fun buildBasicGnssPrompt(context: GnssDetectionContext): String {
        return buildString {
            appendLine("Analyze this GNSS (GPS/satellite) anomaly detection.")
            appendLine()
            appendLine("=== SPOOFING/JAMMING LIKELIHOOD ===")
            appendLine("Spoofing Likelihood: ${String.format("%.0f", context.spoofingLikelihood)}%")
            appendLine("Jamming Likelihood: ${String.format("%.0f", context.jammingLikelihood)}%")
            appendLine()
            appendLine("=== CONSTELLATION ANALYSIS ===")
            appendLine("Observed: ${context.constellations.joinToString { it.code }}")
            appendLine("Satellite Count: ${context.satelliteCount}")
            appendLine()
            appendLine("=== SIGNAL ANALYSIS ===")
            appendLine("C/N0: ${String.format("%.1f", context.avgCn0)} dB-Hz")
            appendLine("Deviation: ${String.format("%.1f", context.cn0Deviation)} sigma")
            appendLine("Variance: ${String.format("%.2f", context.cn0Variance)}")
            appendLine()
            appendLine("=== GEOMETRY ===")
            appendLine("Score: ${String.format("%.0f", context.geometryScore * 100)}%")
            appendLine("Distribution: ${context.elevationDistribution}")
            appendLine("Low-Elev High-Signal: ${context.lowElevHighSignalCount}")
            appendLine()
            appendLine("=== CLOCK ===")
            appendLine("Drift: ${context.clockDrift / 1_000_000} ms")
            appendLine("Trend: ${context.driftTrend.displayName}")
            appendLine("Jumps: ${context.driftJumpCount}")
            appendLine()
            appendLine("Provide assessment of whether GPS is being spoofed or jammed.")
        }
    }
}

/**
 * Context data for GNSS detection handling.
 * Contains all the analysis data needed to make detection decisions.
 */
data class GnssDetectionContext(
    // Identification
    val id: String? = null,
    val timestamp: Long = System.currentTimeMillis(),

    // Constellation data
    val constellations: Set<ConstellationType>,
    val satelliteCount: Int,

    // C/N0 (signal-to-noise) analysis
    val avgCn0: Double,
    val cn0Deviation: Double,  // Standard deviations from baseline
    val cn0Variance: Double,

    // Clock drift analysis
    val clockDrift: Long,  // Cumulative drift in nanoseconds
    val driftTrend: DriftTrend,
    val driftJumpCount: Int,

    // Geometry analysis
    val geometryScore: Float,  // 0-1.0, higher is better
    val elevationDistribution: String,
    val azimuthCoverage: Float,  // 0-100%
    val lowElevHighSignalCount: Int,

    // Jamming indicator
    val jammingIndicator: Float,  // 0-100%

    // Calculated likelihoods
    val spoofingLikelihood: Float,  // 0-100%
    val jammingLikelihood: Float,  // 0-100%

    // Contributing factors
    val spoofingIndicators: List<String>,
    val jammingIndicators: List<String>,

    // Location
    val latitude: Double? = null,
    val longitude: Double? = null,

    // Detection method override (if known from anomaly)
    val detectionMethod: DetectionMethod? = null,

    // Raw data for AI prompts
    val rawAnomaly: GnssAnomaly? = null,
    val rawAnalysis: GnssAnomalyAnalysis? = null
)

/**
 * Constellation fingerprint result
 */
data class ConstellationFingerprint(
    val observed: Set<ConstellationType>,
    val expected: Set<ConstellationType>,
    val missing: Set<ConstellationType>,
    val unexpected: Set<ConstellationType>,
    val matchScore: Int,  // 0-100%
    val isSuspicious: Boolean
)

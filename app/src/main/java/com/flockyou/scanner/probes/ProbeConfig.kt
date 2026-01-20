package com.flockyou.scanner.probes

/**
 * Master catalog of all probe types supported by the Flock system.
 * Organized by target domain spectrum.
 */

enum class ProbeCategory(val displayName: String, val colorHex: Long) {
    PUBLIC_SAFETY("Public Safety & Fleet", 0xFF2196F3),      // Blue
    INFRASTRUCTURE("Infrastructure & Utilities", 0xFF4CAF50), // Green
    INDUSTRIAL("Industrial & Commercial", 0xFF9E9E9E),        // Grey
    PHYSICAL_ACCESS("Physical Access & Security", 0xFFF44336), // Red
    DIGITAL("Digital Peripherals", 0xFF9C27B0)                 // Purple
}

enum class ProbeType(val displayName: String) {
    PASSIVE("Passive"),     // Listen only - generally legal
    ACTIVE("Active"),       // Transmit/probe - may require authorization
    PHYSICAL("Physical")    // Wired tap - requires physical access
}

enum class ProbeHardware(val displayName: String) {
    BLE("Bluetooth LE"),
    WIFI("Wi-Fi"),
    SUBGHZ("Sub-GHz"),
    INFRARED("Infrared"),
    NFC("NFC"),
    GPIO("GPIO"),
    NRF24("NRF24"),
    ONEWIRE("1-Wire"),
    RFID_LF("125kHz RFID")
}

/**
 * Definition of a single probe capability.
 */
data class ProbeDefinition(
    val id: String,
    val name: String,
    val category: ProbeCategory,
    val type: ProbeType,
    val hardware: ProbeHardware,
    val targetSystem: String,
    val mechanism: String,
    val requiresConsent: Boolean = false,
    val consentWarning: String? = null,
    val enabled: Boolean = true
)

/**
 * Master probe catalog - all 35 probes from the specification.
 */
object ProbeCatalog {

    // ========================================================================
    // I. Public Safety & Fleet (Blue Spectrum)
    // ========================================================================

    val BLUE_CANARY = ProbeDefinition(
        id = "blue_canary",
        name = "Blue Canary",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.BLE,
        targetSystem = "Body Cams / Holsters",
        mechanism = "Scan for Axon/WatchGuard OUIs & 'Recording' flags in Manufacturer Data"
    )

    val BLUEFORCE_HANDSHAKE = ProbeDefinition(
        id = "blueforce_handshake",
        name = "BlueForce Handshake",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.BLE,
        targetSystem = "Body Cams / Holsters",
        mechanism = "Send SCAN_REQ to force Model Name/Unit ID in SCAN_RSP",
        requiresConsent = true,
        consentWarning = "Active BLE scanning is detectable. Use only for authorized testing."
    )

    val THE_CHIRPER = ProbeDefinition(
        id = "the_chirper",
        name = "The Chirper",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "LoJack / SVR",
        mechanism = "Listen on 173.075 MHz for stolen vehicle tracking beacons"
    )

    val MDT_PROFILER = ProbeDefinition(
        id = "mdt_profiler",
        name = "MDT Profiler",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.WIFI,
        targetSystem = "Police Laptops",
        mechanism = "Sniff Probe Requests for fleet SSIDs (UNIT_WIFI, NETMOTION)"
    )

    val HONEY_POTTER = ProbeDefinition(
        id = "honey_potter",
        name = "Honey-Potter",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.WIFI,
        targetSystem = "Police Laptops",
        mechanism = "Broadcast Probe Requests for fleet SSIDs to force hidden MDTs to decloak",
        requiresConsent = true,
        consentWarning = "Broadcasting probe requests may trigger network security alerts."
    )

    val OPTICOM_LISTENER = ProbeDefinition(
        id = "opticom_listener",
        name = "Opticom Listener",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.INFRARED,
        targetSystem = "Traffic Preemption",
        mechanism = "Detect 14Hz/10Hz IR strobes from approaching emergency vehicles"
    )

    val OPTICOM_VERIFIER = ProbeDefinition(
        id = "opticom_verifier",
        name = "Opticom Verifier",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.INFRARED,
        targetSystem = "Traffic Preemption",
        mechanism = "Emit 14Hz strobe to trigger Confirmation Light on traffic signals",
        requiresConsent = true,
        consentWarning = "WARNING: Emitting emergency traffic signals on public roads is ILLEGAL. Use only for authorized infrastructure auditing on closed courses."
    )

    val P25_ROAR = ProbeDefinition(
        id = "p25_roar",
        name = "P25 Roar",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Police Radio",
        mechanism = "Detect continuous Control Channel digital noise on 700/800 MHz"
    )

    val TPMS_LISTENER = ProbeDefinition(
        id = "tpms_listener",
        name = "TPMS Listener",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Fleet Tires",
        mechanism = "Log tire pressure (315/433 MHz). High PSI (>40) = Interceptor/Armored"
    )

    val TIRE_KICKER = ProbeDefinition(
        id = "tire_kicker",
        name = "Tire Kicker",
        category = ProbeCategory.PUBLIC_SAFETY,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.RFID_LF,
        targetSystem = "Fleet Tires",
        mechanism = "Transmit 125kHz LF burst to wake sleeping TPMS sensors",
        requiresConsent = true,
        consentWarning = "LF transmission consumes significant Flipper battery. Duration capped at 5 seconds."
    )

    // ========================================================================
    // II. Infrastructure & Utilities (Grid Spectrum)
    // ========================================================================

    val POWER_GRID_MAP = ProbeDefinition(
        id = "power_grid_map",
        name = "Power Grid Map",
        category = ProbeCategory.INFRASTRUCTURE,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Smart Meters (AMR)",
        mechanism = "Decode SCM/IDM packets (900/433 MHz) for consumption & tamper flags"
    )

    val ZIGBEE_KNOCKER = ProbeDefinition(
        id = "zigbee_knocker",
        name = "Zigbee Knocker",
        category = ProbeCategory.INFRASTRUCTURE,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.WIFI, // 2.4GHz via ESP32
        targetSystem = "Smart Meters (AMI)",
        mechanism = "Send Beacon Request to map mesh network density & orphan nodes",
        requiresConsent = true,
        consentWarning = "Active Zigbee probing may disrupt smart home devices. Use in controlled environments only."
    )

    val DRONE_ID = ProbeDefinition(
        id = "drone_id",
        name = "Drone ID",
        category = ProbeCategory.INFRASTRUCTURE,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.WIFI,
        targetSystem = "UAVs",
        mechanism = "Decode ASTM F3411 Remote ID to locate Pilot & Drone"
    )

    val INDUCTIVE_LOOP = ProbeDefinition(
        id = "inductive_loop",
        name = "Inductive Loop",
        category = ProbeCategory.INFRASTRUCTURE,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.GPIO,
        targetSystem = "Traffic Sensors",
        mechanism = "Measure baseline resonance (20-150 kHz) of road sensors"
    )

    val GHOST_CAR = ProbeDefinition(
        id = "ghost_car",
        name = "Ghost Car",
        category = ProbeCategory.INFRASTRUCTURE,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.GPIO,
        targetSystem = "Traffic Sensors",
        mechanism = "Pulse coil at resonant frequency to simulate vehicle presence",
        requiresConsent = true,
        consentWarning = "WARNING: Manipulating traffic sensors on public roads is ILLEGAL. Use only for authorized testing."
    )

    val LORAWAN_MAP = ProbeDefinition(
        id = "lorawan_map",
        name = "LoRaWAN Map",
        category = ProbeCategory.INFRASTRUCTURE,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Smart City",
        mechanism = "Map City Gateways via Join Request RSSI/SNR analysis"
    )

    // ========================================================================
    // III. Industrial & Commercial (Grey Spectrum)
    // ========================================================================

    val CRANE_OPERATOR = ProbeDefinition(
        id = "crane_operator",
        name = "Crane Operator",
        category = ProbeCategory.INDUSTRIAL,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Heavy Machinery",
        mechanism = "Track 'Dead Man' switch heartbeats to monitor active equipment"
    )

    val PAGER_SNOOPS = ProbeDefinition(
        id = "pager_snoops",
        name = "Pager Snoops",
        category = ProbeCategory.INDUSTRIAL,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Restaurants/Hospitality",
        mechanism = "Decode unencrypted POCSAG text messages (~450 MHz)"
    )

    val ESL_AUDIT = ProbeDefinition(
        id = "esl_audit",
        name = "ESL Audit",
        category = ProbeCategory.INDUSTRIAL,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Shelf Labels",
        mechanism = "Log price update timestamps to detect surge pricing logic"
    )

    val THERMAL_MAP = ProbeDefinition(
        id = "thermal_map",
        name = "Thermal Map",
        category = ProbeCategory.INDUSTRIAL,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Server Rooms",
        mechanism = "Decode env sensors (Acurite/LaCrosse) for hot-spot detection"
    )

    val OPTICAL_BRIDGE = ProbeDefinition(
        id = "optical_bridge",
        name = "Optical Bridge",
        category = ProbeCategory.INDUSTRIAL,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.INFRARED,
        targetSystem = "Industrial Meters",
        mechanism = "Brute-force/Read IR ports (IEC 62056) on air-gapped utility meters",
        requiresConsent = true,
        consentWarning = "Accessing utility meters may require authorization from the utility company."
    )

    // ========================================================================
    // IV. Physical Access & Security (Red Spectrum)
    // ========================================================================

    val VICINITY_TRACKER = ProbeDefinition(
        id = "vicinity_tracker",
        name = "Vicinity Tracker",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.NFC,
        targetSystem = "Ski/Library Tags",
        mechanism = "Detect ISO 15693 tags at long range (>1m) for crowd flow"
    )

    val BLIND_SPOTTER = ProbeDefinition(
        id = "blind_spotter",
        name = "Blind Spotter",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Perimeter Alarms",
        mechanism = "Walk-test PIR sensors to map detection dead zones"
    )

    val SLEEP_DENIAL = ProbeDefinition(
        id = "sleep_denial",
        name = "Sleep Denial",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Perimeter Alarms",
        mechanism = "Replay triggers to cause alarm fatigue (Denial of Sleep)",
        requiresConsent = true,
        consentWarning = "WARNING: Deliberately triggering alarms may constitute criminal mischief. Use only for authorized penetration testing."
    )

    val WIRE_TAP = ProbeDefinition(
        id = "wire_tap",
        name = "Wire Tap",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.PHYSICAL,
        hardware = ProbeHardware.GPIO,
        targetSystem = "Card Readers",
        mechanism = "Tap Wiegand D0/D1 wires to log raw card data",
        requiresConsent = true,
        consentWarning = "Physical interception requires explicit written authorization."
    )

    val REPLAY_INJECTOR = ProbeDefinition(
        id = "replay_injector",
        name = "Replay Injector",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.PHYSICAL,
        hardware = ProbeHardware.GPIO,
        targetSystem = "Card Readers",
        mechanism = "Replay recorded Wiegand signals to bypass reader auth",
        requiresConsent = true,
        consentWarning = "WARNING: Bypassing access control is illegal without authorization. Use only for authorized penetration testing."
    )

    val MAGSPOOF = ProbeDefinition(
        id = "magspoof",
        name = "MagSpoof",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.GPIO,
        targetSystem = "Magstripe Readers",
        mechanism = "Emulate magnetic stripe data via electromagnetic pulses",
        requiresConsent = true,
        consentWarning = "WARNING: Magstripe emulation for unauthorized transactions is fraud. Use only for authorized testing."
    )

    val MASTER_KEY = ProbeDefinition(
        id = "master_key",
        name = "Master Key",
        category = ProbeCategory.PHYSICAL_ACCESS,
        type = ProbeType.PHYSICAL,
        hardware = ProbeHardware.ONEWIRE,
        targetSystem = "iButton / Dallas",
        mechanism = "Clone/Emulate DS1990A keys for heavy equipment/POS access",
        requiresConsent = true,
        consentWarning = "iButton cloning requires authorization from the asset owner."
    )

    // ========================================================================
    // V. Digital Peripherals (Cyber Spectrum)
    // ========================================================================

    val MOUSEJACKER = ProbeDefinition(
        id = "mousejacker",
        name = "MouseJacker",
        category = ProbeCategory.DIGITAL,
        type = ProbeType.ACTIVE,
        hardware = ProbeHardware.NRF24,
        targetSystem = "Mice/Keyboards",
        mechanism = "Inject keystrokes into vulnerable wireless HID dongles",
        requiresConsent = true,
        consentWarning = "WARNING: Keystroke injection without consent is unauthorized access. Use only for authorized security testing."
    )

    val PROMISCUOUS_SCAN = ProbeDefinition(
        id = "promiscuous_scan",
        name = "Promiscuous Scan",
        category = ProbeCategory.DIGITAL,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.NRF24,
        targetSystem = "Mice/Keyboards",
        mechanism = "Detect vulnerable non-Bluetooth wireless peripherals in an office"
    )

    val MIC_CHECK = ProbeDefinition(
        id = "mic_check",
        name = "Mic Check",
        category = ProbeCategory.DIGITAL,
        type = ProbeType.PASSIVE,
        hardware = ProbeHardware.SUBGHZ,
        targetSystem = "Wireless Mics",
        mechanism = "Demodulate wideband FM audio from unencrypted stage mics"
    )

    // ========================================================================
    // Catalog Access
    // ========================================================================

    val ALL_PROBES: List<ProbeDefinition> = listOf(
        // Public Safety
        BLUE_CANARY, BLUEFORCE_HANDSHAKE, THE_CHIRPER, MDT_PROFILER, HONEY_POTTER,
        OPTICOM_LISTENER, OPTICOM_VERIFIER, P25_ROAR, TPMS_LISTENER, TIRE_KICKER,
        // Infrastructure
        POWER_GRID_MAP, ZIGBEE_KNOCKER, DRONE_ID, INDUCTIVE_LOOP, GHOST_CAR, LORAWAN_MAP,
        // Industrial
        CRANE_OPERATOR, PAGER_SNOOPS, ESL_AUDIT, THERMAL_MAP, OPTICAL_BRIDGE,
        // Physical Access
        VICINITY_TRACKER, BLIND_SPOTTER, SLEEP_DENIAL, WIRE_TAP, REPLAY_INJECTOR, MAGSPOOF, MASTER_KEY,
        // Digital
        MOUSEJACKER, PROMISCUOUS_SCAN, MIC_CHECK
    )

    val PASSIVE_PROBES = ALL_PROBES.filter { it.type == ProbeType.PASSIVE }
    val ACTIVE_PROBES = ALL_PROBES.filter { it.type == ProbeType.ACTIVE }
    val PHYSICAL_PROBES = ALL_PROBES.filter { it.type == ProbeType.PHYSICAL }

    val PROBES_REQUIRING_CONSENT = ALL_PROBES.filter { it.requiresConsent }

    val BY_CATEGORY: Map<ProbeCategory, List<ProbeDefinition>> = ALL_PROBES.groupBy { it.category }
    val BY_HARDWARE: Map<ProbeHardware, List<ProbeDefinition>> = ALL_PROBES.groupBy { it.hardware }

    fun getById(id: String): ProbeDefinition? = ALL_PROBES.find { it.id == id }
}

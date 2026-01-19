package com.flockyou.monitoring

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.SignalStrength as DetectionSignalStrength
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
            
            // Check if frequency is in NTN band
            fun isNTNFrequency(freqMHz: Int): Boolean {
                return (freqMHz in L_BAND_LOW..L_BAND_HIGH) ||
                       (freqMHz in L_BAND_UL_LOW..L_BAND_UL_HIGH) ||
                       (freqMHz in S_BAND_LOW..S_BAND_HIGH) ||
                       (freqMHz in S_BAND_UL_LOW..S_BAND_UL_HIGH)
            }
        }
        
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
        const val DEFAULT_PERIODIC_CHECK_INTERVAL_MS = 5000L
        const val DEFAULT_ANOMALY_DETECTION_INTERVAL_MS = 10000L
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
        val capabilities: SatelliteCapabilities = SatelliteCapabilities()
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
    
    data class SatelliteCapabilities(
        val supportsSMS: Boolean = false,
        val supportsMMS: Boolean = false,
        val supportsVoice: Boolean = false,
        val supportsData: Boolean = false,
        val supportsEmergency: Boolean = false,
        val supportsLocationSharing: Boolean = false,
        val maxMessageLength: Int? = null
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
        UNEXPECTED_SATELLITE_CONNECTION,    // Connected to satellite when terrestrial available
        FORCED_SATELLITE_HANDOFF,           // Rapid or suspicious handoff to satellite
        SUSPICIOUS_NTN_PARAMETERS,          // Unusual NTN config suggesting spoofing
        UNKNOWN_SATELLITE_NETWORK,          // Unrecognized satellite network
        SATELLITE_IN_COVERED_AREA,          // Satellite used despite good coverage
        RAPID_SATELLITE_SWITCHING,          // Abnormal satellite handoff patterns
        NTN_BAND_MISMATCH,                  // Claimed satellite but wrong frequency
        TIMING_ADVANCE_ANOMALY,             // NTN timing doesn't match claimed orbit
        EPHEMERIS_MISMATCH,                 // Satellite position doesn't match known data
        DOWNGRADE_TO_SATELLITE              // Forced from better tech to satellite
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
        
        // For NTN, we expect specific timing characteristics
        // LEO: ~30ms latency, GEO: ~250ms latency
        
        when (state.provider) {
            SatelliteProvider.STARLINK -> {
                // Starlink LEO should have consistent timing
                // Anomaly if timing suggests ground-based spoofing
            }
            SatelliteProvider.SKYLO -> {
                // Skylo uses various satellites
            }
            else -> {}
        }
    }
    
    /**
     * Check device satellite support
     */
    private fun checkDeviceSatelliteSupport() {
        val hasSatelliteFeature = context.packageManager.hasSystemFeature(
            "android.hardware.telephony.satellite"
        )
        
        val deviceModel = Build.MODEL.uppercase()
        val isPixel9Or10 = deviceModel.contains("PIXEL 9") || deviceModel.contains("PIXEL 10")
        
        Log.i(TAG, "Device satellite support: feature=$hasSatelliteFeature, model=$deviceModel, pixel9or10=$isPixel9Or10")
        
        // Pixel 9/10 have Skylo NTN support
        // Check for T-Mobile Starlink compatibility (most phones from last 4 years)
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
            deviceSupported = isDeviceSatelliteCapable()
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
        val deviceSupported: Boolean
    )

    /**
     * Convert satellite anomaly to Detection for storage
     */
    fun anomalyToDetection(anomaly: SatelliteAnomaly): Detection {
        val detectionMethod = when (anomaly.type) {
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
        }

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
        return when (type) {
            SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION -> "Unexpected Satellite"
            SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> "Forced Handoff"
            SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS -> "Suspicious NTN"
            SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK -> "Unknown Network"
            SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> "Satellite in Coverage"
            SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> "Rapid Switching"
            SatelliteAnomalyType.NTN_BAND_MISMATCH -> "Band Mismatch"
            SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY -> "Timing Anomaly"
            SatelliteAnomalyType.EPHEMERIS_MISMATCH -> "Ephemeris Mismatch"
            SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> "Network Downgrade"
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
}

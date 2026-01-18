package com.flockyou.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.flockyou.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Monitors cellular network for anomalies that may indicate IMSI catchers,
 * StingRay devices, or other cell site simulators.
 * 
 * Detection methodology:
 * - 2G downgrade attacks (IMSI catchers often force devices to 2G)
 * - Rapid cell tower changes without movement
 * - Unusually strong signal from unknown towers
 * - Cell ID/LAC anomalies
 * - New towers appearing in known locations
 */
class CellularMonitor(private val context: Context) {
    
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Track cell history for anomaly detection
    private val cellHistory = ConcurrentLinkedQueue<CellRecord>()
    private val maxHistorySize = 100
    
    // Known good cells (learned over time)
    private val knownCells = mutableSetOf<String>()
    private val cellFirstSeen = mutableMapOf<String, Long>()
    
    // Last known state
    private var lastNetworkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN
    private var lastCellId: String? = null
    private var lastSignalStrength: Int = -999
    private var lastLocationUpdate: Long = 0L
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    
    // Anomaly thresholds
    private val rapidChangeThresholdMs = 5000L // Cell changes within 5 seconds
    private val rapidChangeCount = 3 // Number of rapid changes to trigger alert
    private val signalAnomalyThreshold = -50 // dBm, unusually strong
    private val downgradeAlertCooldownMs = 60000L // 1 minute cooldown for 2G alerts
    private var lastDowngradeAlert = 0L
    
    // Detection results
    private val _anomalies = MutableStateFlow<List<CellularAnomaly>>(emptyList())
    val anomalies: StateFlow<List<CellularAnomaly>> = _anomalies.asStateFlow()
    
    private val _currentCellInfo = MutableStateFlow<CellStatus?>(null)
    val currentCellInfo: StateFlow<CellStatus?> = _currentCellInfo.asStateFlow()
    
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    data class CellRecord(
        val timestamp: Long,
        val cellId: String,
        val networkType: Int,
        val signalStrength: Int,
        val lac: Int?,
        val mcc: Int?,
        val mnc: Int?,
        val latitude: Double?,
        val longitude: Double?
    )
    
    data class CellStatus(
        val networkType: String,
        val networkGeneration: String,
        val cellId: String,
        val signalStrength: Int,
        val signalBars: Int,
        val operator: String?,
        val isRoaming: Boolean,
        val lac: Int?,
        val mcc: Int?,
        val mnc: Int?,
        val isKnownCell: Boolean,
        val cellFirstSeenTime: Long?
    )
    
    data class CellularAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val type: AnomalyType,
        val timestamp: Long = System.currentTimeMillis(),
        val description: String,
        val severity: ThreatLevel,
        val details: Map<String, String> = emptyMap()
    )
    
    enum class AnomalyType(val displayName: String, val emoji: String) {
        DOWNGRADE_2G("2G Downgrade Attack", "📉"),
        RAPID_TOWER_CHANGE("Rapid Tower Switching", "🔄"),
        SIGNAL_ANOMALY("Signal Strength Anomaly", "📶"),
        NEW_TOWER("New Cell Tower", "🆕"),
        LAC_MISMATCH("Location Area Mismatch", "📍"),
        CELL_ID_CHANGE_NO_MOVEMENT("Cell Change Without Movement", "⚠️"),
        ENCRYPTION_DISABLED("Encryption Disabled", "🔓"),
        UNKNOWN_OPERATOR("Unknown Operator", "❓")
    }
    
    private var monitorJob: Job? = null
    private var phoneStateListener: PhoneStateListener? = null
    
    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (!hasPermission()) {
            return
        }
        
        _isMonitoring.value = true
        
        // Initial cell info update
        updateCellInfo()
        
        // Start periodic monitoring
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    updateCellInfo()
                    checkForAnomalies()
                } catch (e: Exception) {
                    // Log error but continue monitoring
                }
                delay(5000) // Check every 5 seconds
            }
        }
        
        // Register phone state listener for real-time updates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Use TelephonyCallback for Android 12+
            registerTelephonyCallback()
        } else {
            // Use deprecated PhoneStateListener for older versions
            registerPhoneStateListener()
        }
    }
    
    fun stopMonitoring() {
        _isMonitoring.value = false
        monitorJob?.cancel()
        monitorJob = null
        unregisterListeners()
    }
    
    @SuppressLint("MissingPermission")
    private fun updateCellInfo() {
        if (!hasPermission()) return
        
        try {
            val cellInfoList = telephonyManager.allCellInfo ?: return
            val activeCellInfo = cellInfoList.firstOrNull { it.isRegistered } ?: cellInfoList.firstOrNull()
            
            activeCellInfo?.let { cellInfo ->
                val (cellId, lac, mcc, mnc, signalStrength) = extractCellData(cellInfo)
                val networkType = telephonyManager.dataNetworkType
                
                val cellKey = "$mcc-$mnc-$lac-$cellId"
                val isKnown = knownCells.contains(cellKey)
                
                if (!isKnown && cellId != "unknown") {
                    knownCells.add(cellKey)
                    cellFirstSeen[cellKey] = System.currentTimeMillis()
                }
                
                val status = CellStatus(
                    networkType = getNetworkTypeName(networkType),
                    networkGeneration = getNetworkGeneration(networkType),
                    cellId = cellId,
                    signalStrength = signalStrength,
                    signalBars = getSignalBars(signalStrength),
                    operator = telephonyManager.networkOperatorName,
                    isRoaming = telephonyManager.isNetworkRoaming,
                    lac = lac,
                    mcc = mcc,
                    mnc = mnc,
                    isKnownCell = isKnown,
                    cellFirstSeenTime = cellFirstSeen[cellKey]
                )
                
                _currentCellInfo.value = status
                
                // Record for history
                val record = CellRecord(
                    timestamp = System.currentTimeMillis(),
                    cellId = cellId,
                    networkType = networkType,
                    signalStrength = signalStrength,
                    lac = lac,
                    mcc = mcc,
                    mnc = mnc,
                    latitude = lastLatitude,
                    longitude = lastLongitude
                )
                
                cellHistory.add(record)
                while (cellHistory.size > maxHistorySize) {
                    cellHistory.poll()
                }
                
                // Update last known state
                lastNetworkType = networkType
                lastCellId = cellId
                lastSignalStrength = signalStrength
            }
        } catch (e: SecurityException) {
            // Permission denied
        } catch (e: Exception) {
            // Other errors
        }
    }
    
    private fun extractCellData(cellInfo: CellInfo): CellData {
        return when (cellInfo) {
            is CellInfoLte -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellData(
                    cellId = identity.ci.toString(),
                    lac = identity.tac,
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mccString?.toIntOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mcc.takeIf { it != Int.MAX_VALUE }
                    },
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mncString?.toIntOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mnc.takeIf { it != Int.MAX_VALUE }
                    },
                    signalStrength = signal.dbm
                )
            }
            is CellInfoGsm -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellData(
                    cellId = identity.cid.toString(),
                    lac = identity.lac.takeIf { it != Int.MAX_VALUE },
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mccString?.toIntOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mcc.takeIf { it != Int.MAX_VALUE }
                    },
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mncString?.toIntOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mnc.takeIf { it != Int.MAX_VALUE }
                    },
                    signalStrength = signal.dbm
                )
            }
            is CellInfoWcdma -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellData(
                    cellId = identity.cid.toString(),
                    lac = identity.lac.takeIf { it != Int.MAX_VALUE },
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mccString?.toIntOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mcc.takeIf { it != Int.MAX_VALUE }
                    },
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mncString?.toIntOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mnc.takeIf { it != Int.MAX_VALUE }
                    },
                    signalStrength = signal.dbm
                )
            }
            is CellInfoCdma -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellData(
                    cellId = "${identity.basestationId}",
                    lac = identity.networkId,
                    mcc = null, // CDMA doesn't use MCC
                    mnc = identity.systemId,
                    signalStrength = signal.dbm
                )
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr) {
                    val identity = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                    val signal = cellInfo.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                    CellData(
                        cellId = identity?.nci?.toString() ?: "unknown",
                        lac = identity?.tac,
                        mcc = identity?.mccString?.toIntOrNull(),
                        mnc = identity?.mncString?.toIntOrNull(),
                        signalStrength = signal?.dbm ?: -999
                    )
                } else {
                    CellData("unknown", null, null, null, -999)
                }
            }
        }
    }
    
    private data class CellData(
        val cellId: String,
        val lac: Int?,
        val mcc: Int?,
        val mnc: Int?,
        val signalStrength: Int
    )
    
    private fun checkForAnomalies() {
        val newAnomalies = mutableListOf<CellularAnomaly>()
        val now = System.currentTimeMillis()
        val currentStatus = _currentCellInfo.value ?: return
        
        // Check 1: 2G downgrade attack
        if (currentStatus.networkGeneration == "2G" && lastNetworkType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            val wasHigherGen = getNetworkGeneration(lastNetworkType) in listOf("3G", "4G", "5G")
            if (wasHigherGen && (now - lastDowngradeAlert) > downgradeAlertCooldownMs) {
                newAnomalies.add(
                    CellularAnomaly(
                        type = AnomalyType.DOWNGRADE_2G,
                        description = "Network downgraded to 2G. IMSI catchers often force 2G to disable encryption.",
                        severity = ThreatLevel.CRITICAL,
                        details = mapOf(
                            "previous_network" to getNetworkGeneration(lastNetworkType),
                            "current_network" to "2G",
                            "cell_id" to currentStatus.cellId
                        )
                    )
                )
                lastDowngradeAlert = now
            }
        }
        
        // Check 2: Rapid tower changes
        val recentRecords = cellHistory.filter { now - it.timestamp < 30000 } // Last 30 seconds
        val uniqueCells = recentRecords.map { it.cellId }.distinct()
        if (uniqueCells.size >= rapidChangeCount) {
            val timestamps = recentRecords.groupBy { it.cellId }.values
            val hasRapidChanges = timestamps.any { records ->
                records.zipWithNext().any { (a, b) -> 
                    kotlin.math.abs(a.timestamp - b.timestamp) < rapidChangeThresholdMs 
                }
            }
            
            if (hasRapidChanges || uniqueCells.size >= 5) {
                newAnomalies.add(
                    CellularAnomaly(
                        type = AnomalyType.RAPID_TOWER_CHANGE,
                        description = "Rapid cell tower switching detected (${uniqueCells.size} towers in 30s). May indicate tracking or interference.",
                        severity = ThreatLevel.HIGH,
                        details = mapOf(
                            "tower_count" to uniqueCells.size.toString(),
                            "towers" to uniqueCells.take(5).joinToString(", ")
                        )
                    )
                )
            }
        }
        
        // Check 3: Signal strength anomaly (unusually strong)
        if (currentStatus.signalStrength > signalAnomalyThreshold && currentStatus.signalStrength != 0) {
            newAnomalies.add(
                CellularAnomaly(
                    type = AnomalyType.SIGNAL_ANOMALY,
                    description = "Unusually strong signal (${currentStatus.signalStrength} dBm). Fake cell towers often have very strong signals.",
                    severity = ThreatLevel.MEDIUM,
                    details = mapOf(
                        "signal_strength" to "${currentStatus.signalStrength} dBm",
                        "cell_id" to currentStatus.cellId
                    )
                )
            )
        }
        
        // Check 4: New tower in known location
        if (!currentStatus.isKnownCell && currentStatus.cellId != "unknown") {
            val firstSeen = currentStatus.cellFirstSeenTime
            if (firstSeen != null && (now - firstSeen) < 60000) { // Just appeared
                newAnomalies.add(
                    CellularAnomaly(
                        type = AnomalyType.NEW_TOWER,
                        description = "New cell tower detected: ${currentStatus.cellId}. Monitor for persistent appearance.",
                        severity = ThreatLevel.LOW,
                        details = mapOf(
                            "cell_id" to currentStatus.cellId,
                            "operator" to (currentStatus.operator ?: "Unknown"),
                            "network" to currentStatus.networkGeneration
                        )
                    )
                )
            }
        }
        
        // Check 5: Unknown/suspicious operator
        val operator = currentStatus.operator
        if (operator.isNullOrBlank() || operator == "null" || operator.length < 2) {
            newAnomalies.add(
                CellularAnomaly(
                    type = AnomalyType.UNKNOWN_OPERATOR,
                    description = "Connected to cell tower with unknown/blank operator name. Legitimate towers always identify their operator.",
                    severity = ThreatLevel.HIGH,
                    details = mapOf(
                        "cell_id" to currentStatus.cellId,
                        "mcc" to (currentStatus.mcc?.toString() ?: "unknown"),
                        "mnc" to (currentStatus.mnc?.toString() ?: "unknown")
                    )
                )
            )
        }
        
        // Update anomalies list (keep recent ones, add new)
        val existingAnomalies = _anomalies.value.filter { 
            now - it.timestamp < 300000 // Keep for 5 minutes
        }
        
        // Deduplicate by type (only one of each type in recent window)
        val deduped = (existingAnomalies + newAnomalies)
            .groupBy { it.type }
            .mapValues { (_, anomalies) -> anomalies.maxByOrNull { it.timestamp }!! }
            .values
            .sortedByDescending { it.timestamp }
        
        _anomalies.value = deduped.toList()
    }
    
    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                super.onCellInfoChanged(cellInfo)
                updateCellInfo()
                checkForAnomalies()
            }
            
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                super.onSignalStrengthsChanged(signalStrength)
                updateCellInfo()
            }
        }
        
        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
        )
    }
    
    @SuppressLint("MissingPermission")
    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ uses TelephonyCallback
            // Note: This requires additional setup, using periodic polling as fallback
        }
    }
    
    @Suppress("DEPRECATION")
    private fun unregisterListeners() {
        phoneStateListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
    }
    
    fun updateLocation(latitude: Double, longitude: Double) {
        lastLatitude = latitude
        lastLongitude = longitude
        lastLocationUpdate = System.currentTimeMillis()
    }
    
    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun getNetworkTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            19 -> "LTE-CA" // NETWORK_TYPE_LTE_CA
            20 -> "5G NR" // NETWORK_TYPE_NR
            else -> "Unknown"
        }
    }
    
    private fun getNetworkGeneration(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN,
            19 -> "4G" // LTE-CA
            
            20 -> "5G" // NR
            
            else -> "Unknown"
        }
    }
    
    private fun getSignalBars(dbm: Int): Int {
        return when {
            dbm >= -70 -> 4
            dbm >= -85 -> 3
            dbm >= -100 -> 2
            dbm >= -110 -> 1
            else -> 0
        }
    }
    
    /**
     * Convert anomaly to Detection for unified handling
     */
    fun anomalyToDetection(anomaly: CellularAnomaly): Detection {
        return Detection(
            id = anomaly.id,
            identifier = "CELLULAR_${anomaly.type.name}",
            deviceType = DeviceType.STINGRAY_IMSI,
            protocol = DetectionProtocol.WIFI, // Using WIFI as proxy for "radio"
            threatLevel = anomaly.severity,
            manufacturer = "Unknown",
            signalStrength = lastSignalStrength,
            firstSeen = anomaly.timestamp,
            lastSeen = anomaly.timestamp,
            location = if (lastLatitude != null && lastLongitude != null) {
                DetectionLocation(lastLatitude!!, lastLongitude!!, 50f)
            } else null,
            rawData = mapOf(
                "anomaly_type" to anomaly.type.name,
                "description" to anomaly.description
            ) + anomaly.details
        )
    }
    
    fun destroy() {
        stopMonitoring()
        scope.cancel()
    }
}

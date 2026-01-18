package com.flockyou.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Monitors cellular network for anomalies that could indicate IMSI catchers (StingRay)
 * or other cell site simulators.
 * 
 * Detection methods:
 * 1. Sudden signal strength changes
 * 2. Cell tower ID changes without movement
 * 3. Encryption downgrade (from 4G/5G to 2G)
 * 4. Unknown/suspicious cell IDs
 * 5. Abnormal LAC/TAC values
 */
class CellularMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "CellularMonitor"
        
        // Thresholds for anomaly detection
        private const val SIGNAL_CHANGE_THRESHOLD = 20 // dBm
        private const val MIN_ANOMALY_INTERVAL_MS = 30_000L // 30 seconds between same anomaly type
        private const val CELL_HISTORY_SIZE = 50
        
        // Known suspicious patterns
        private val SUSPICIOUS_MCC_MNC = setOf(
            // Test networks often used by IMSI catchers
            "001-01", "001-00", "999-99"
        )
    }
    
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    
    // Monitoring state
    private var isMonitoring = false
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    
    // Cell history for pattern detection
    private val cellHistory = mutableListOf<CellSnapshot>()
    private var lastKnownCell: CellSnapshot? = null
    private var lastAnomalyTimes = mutableMapOf<AnomalyType, Long>()
    
    // Anomalies flow
    private val _anomalies = MutableStateFlow<List<CellularAnomaly>>(emptyList())
    val anomalies: StateFlow<List<CellularAnomaly>> = _anomalies.asStateFlow()
    
    // Current cell status flow
    private val _cellStatus = MutableStateFlow<CellStatus?>(null)
    val cellStatus: StateFlow<CellStatus?> = _cellStatus.asStateFlow()
    
    private val detectedAnomalies = mutableListOf<CellularAnomaly>()
    
    // Track known cells
    private val knownCells = mutableSetOf<String>()
    
    // Callback for cell info changes
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null // TelephonyCallback for API 31+
    
    data class CellSnapshot(
        val timestamp: Long,
        val cellId: Int?,
        val lac: Int?, // Location Area Code (2G/3G)
        val tac: Int?, // Tracking Area Code (4G/5G)
        val mcc: String?,
        val mnc: String?,
        val signalStrength: Int, // dBm
        val networkType: Int,
        val latitude: Double?,
        val longitude: Double?
    )
    
    data class CellularAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: AnomalyType,
        val severity: ThreatLevel,
        val description: String,
        val cellId: Int?,
        val previousCellId: Int?,
        val signalStrength: Int,
        val previousSignalStrength: Int?,
        val networkType: String,
        val previousNetworkType: String?,
        val mccMnc: String?,
        val latitude: Double?,
        val longitude: Double?
    )
    
    enum class AnomalyType(val displayName: String, val baseThreatScore: Int, val emoji: String) {
        SIGNAL_SPIKE("Sudden Signal Spike", 70, "📶"),
        CELL_TOWER_CHANGE("Unexpected Cell Change", 60, "🗼"),
        ENCRYPTION_DOWNGRADE("Encryption Downgrade", 95, "🔓"),
        SUSPICIOUS_NETWORK("Suspicious Network ID", 90, "⚠️"),
        UNKNOWN_CELL("Unknown Cell Tower", 50, "❓"),
        RAPID_CELL_SWITCHING("Rapid Cell Switching", 75, "🔄"),
        LAC_TAC_ANOMALY("Location Area Anomaly", 65, "📍")
    }
    
    /**
     * Current cell tower status for UI display
     */
    data class CellStatus(
        val cellId: String,
        val lac: Int?,
        val tac: Int?,
        val mcc: String?,
        val mnc: String?,
        val operator: String?,
        val networkType: String,
        val networkGeneration: String,
        val signalStrength: Int,
        val signalBars: Int,
        val isKnownCell: Boolean,
        val isRoaming: Boolean
    )
    
    /**
     * Record of a seen cell tower for history
     */
    data class SeenCellTower(
        val cellId: String,
        val lac: Int?,
        val tac: Int?,
        val mcc: String?,
        val mnc: String?,
        val operator: String?,
        val networkType: String,
        val networkGeneration: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val seenCount: Int,
        val minSignal: Int,
        val maxSignal: Int,
        val lastSignal: Int,
        val latitude: Double?,
        val longitude: Double?
    )
    
    // Cell tower history flow
    private val _seenCellTowers = MutableStateFlow<List<SeenCellTower>>(emptyList())
    val seenCellTowers: StateFlow<List<SeenCellTower>> = _seenCellTowers.asStateFlow()
    
    private val seenCellTowerMap = mutableMapOf<String, SeenCellTower>()
    
    fun startMonitoring() {
        if (isMonitoring) return
        
        if (!hasPermissions()) {
            Log.w(TAG, "Missing required permissions for cellular monitoring")
            return
        }
        
        isMonitoring = true
        Log.d(TAG, "Starting cellular monitoring")
        
        registerCellListener()
        
        // Take initial snapshot
        takeCellSnapshot()?.let { snapshot ->
            cellHistory.add(snapshot)
            lastKnownCell = snapshot
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        unregisterCellListener()
        Log.d(TAG, "Stopped cellular monitoring")
    }
    
    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }
    
    fun destroy() {
        stopMonitoring()
        cellHistory.clear()
        detectedAnomalies.clear()
    }
    
    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    @Suppress("DEPRECATION")
    private fun registerCellListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Use TelephonyCallback for API 31+
                val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        onCellInfoUpdate(cellInfo)
                    }
                }
                telephonyCallback = callback
                telephonyManager.registerTelephonyCallback(
                    context.mainExecutor,
                    callback
                )
            } else {
                // Use deprecated PhoneStateListener for older APIs
                phoneStateListener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                        cellInfo?.let { onCellInfoUpdate(it) }
                    }
                    
                    @Deprecated("Deprecated in Java")
                    override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength?) {
                        onSignalUpdate(signalStrength)
                    }
                }
                telephonyManager.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception registering cell listener", e)
        }
    }
    
    @Suppress("DEPRECATION")
    private fun unregisterCellListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                phoneStateListener?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering cell listener", e)
        }
        phoneStateListener = null
        telephonyCallback = null
    }
    
    private fun onCellInfoUpdate(cellInfoList: List<CellInfo>) {
        if (!isMonitoring) return
        
        val snapshot = takeCellSnapshot() ?: return
        
        // Add to history
        cellHistory.add(snapshot)
        if (cellHistory.size > CELL_HISTORY_SIZE) {
            cellHistory.removeAt(0)
        }
        
        // Update cell status for UI
        updateCellStatus(snapshot)
        
        // Analyze for anomalies
        lastKnownCell?.let { previous ->
            analyzeForAnomalies(previous, snapshot)
        }
        
        lastKnownCell = snapshot
    }
    
    @Suppress("MissingPermission")
    private fun updateCellStatus(snapshot: CellSnapshot) {
        val cellIdStr = snapshot.cellId?.toString() ?: "Unknown"
        val isKnown = cellIdStr in knownCells
        if (cellIdStr != "Unknown") {
            knownCells.add(cellIdStr)
        }
        
        val isRoaming = try {
            telephonyManager.isNetworkRoaming
        } catch (e: Exception) {
            false
        }
        
        val operator = try {
            telephonyManager.networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        
        val networkTypeName = getNetworkTypeName(snapshot.networkType)
        val networkGen = "${getNetworkGeneration(snapshot.networkType)}G"
        
        _cellStatus.value = CellStatus(
            cellId = cellIdStr,
            lac = snapshot.lac,
            tac = snapshot.tac,
            mcc = snapshot.mcc,
            mnc = snapshot.mnc,
            operator = operator,
            networkType = networkTypeName,
            networkGeneration = networkGen,
            signalStrength = snapshot.signalStrength,
            signalBars = signalDbmToBars(snapshot.signalStrength),
            isKnownCell = isKnown,
            isRoaming = isRoaming
        )
        
        // Track cell tower history
        if (cellIdStr != "Unknown") {
            trackCellTower(
                cellId = cellIdStr,
                lac = snapshot.lac,
                tac = snapshot.tac,
                mcc = snapshot.mcc,
                mnc = snapshot.mnc,
                operator = operator,
                networkType = networkTypeName,
                networkGeneration = networkGen,
                signalStrength = snapshot.signalStrength,
                latitude = snapshot.latitude,
                longitude = snapshot.longitude
            )
        }
    }
    
    private fun trackCellTower(
        cellId: String,
        lac: Int?,
        tac: Int?,
        mcc: String?,
        mnc: String?,
        operator: String?,
        networkType: String,
        networkGeneration: String,
        signalStrength: Int,
        latitude: Double?,
        longitude: Double?
    ) {
        val now = System.currentTimeMillis()
        val existing = seenCellTowerMap[cellId]
        
        if (existing != null) {
            // Update existing tower
            seenCellTowerMap[cellId] = existing.copy(
                lastSeen = now,
                seenCount = existing.seenCount + 1,
                minSignal = minOf(existing.minSignal, signalStrength),
                maxSignal = maxOf(existing.maxSignal, signalStrength),
                lastSignal = signalStrength,
                latitude = latitude ?: existing.latitude,
                longitude = longitude ?: existing.longitude
            )
        } else {
            // Add new tower
            seenCellTowerMap[cellId] = SeenCellTower(
                cellId = cellId,
                lac = lac,
                tac = tac,
                mcc = mcc,
                mnc = mnc,
                operator = operator,
                networkType = networkType,
                networkGeneration = networkGeneration,
                firstSeen = now,
                lastSeen = now,
                seenCount = 1,
                minSignal = signalStrength,
                maxSignal = signalStrength,
                lastSignal = signalStrength,
                latitude = latitude,
                longitude = longitude
            )
        }
        
        // Update flow
        _seenCellTowers.value = seenCellTowerMap.values.toList().sortedByDescending { it.lastSeen }
    }
    
    fun clearCellHistory() {
        seenCellTowerMap.clear()
        _seenCellTowers.value = emptyList()
        knownCells.clear()
    }
    
    private fun signalDbmToBars(dbm: Int): Int {
        return when {
            dbm >= -70 -> 4
            dbm >= -85 -> 3
            dbm >= -100 -> 2
            dbm >= -110 -> 1
            else -> 0
        }
    }
    
    private fun onSignalUpdate(signalStrength: android.telephony.SignalStrength?) {
        if (!isMonitoring || signalStrength == null) return
        
        // Check for sudden signal changes
        val currentDbm = getSignalDbm(signalStrength)
        lastKnownCell?.let { previous ->
            val change = kotlin.math.abs(currentDbm - previous.signalStrength)
            if (change > SIGNAL_CHANGE_THRESHOLD) {
                reportAnomaly(
                    type = AnomalyType.SIGNAL_SPIKE,
                    description = "Signal changed by ${change}dBm (${previous.signalStrength} → $currentDbm)",
                    cellId = previous.cellId,
                    previousCellId = previous.cellId,
                    signalStrength = currentDbm,
                    previousSignalStrength = previous.signalStrength,
                    networkType = getNetworkTypeName(previous.networkType),
                    previousNetworkType = null,
                    mccMnc = "${previous.mcc}-${previous.mnc}"
                )
            }
        }
    }
    
    private fun getSignalDbm(signalStrength: android.telephony.SignalStrength): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths.firstOrNull()?.dbm ?: -100
        } else {
            @Suppress("DEPRECATION")
            when {
                signalStrength.isGsm -> -113 + 2 * signalStrength.gsmSignalStrength
                else -> signalStrength.cdmaDbm
            }
        }
    }
    
    @Suppress("MissingPermission")
    private fun takeCellSnapshot(): CellSnapshot? {
        if (!hasPermissions()) return null
        
        try {
            val cellInfoList = telephonyManager.allCellInfo ?: return null
            val primaryCell = cellInfoList.firstOrNull { it.isRegistered } ?: cellInfoList.firstOrNull()
            
            return primaryCell?.let { cellInfo ->
                extractCellSnapshot(cellInfo)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting cell info", e)
            return null
        }
    }
    
    private fun extractCellSnapshot(cellInfo: CellInfo): CellSnapshot {
        var cellId: Int? = null
        var lac: Int? = null
        var tac: Int? = null
        var mcc: String? = null
        var mnc: String? = null
        var signalDbm = -100
        var networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN
        
        when (cellInfo) {
            is CellInfoLte -> {
                cellId = cellInfo.cellIdentity.ci.takeIf { it != Int.MAX_VALUE }
                tac = cellInfo.cellIdentity.tac.takeIf { it != Int.MAX_VALUE }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mcc = cellInfo.cellIdentity.mccString
                    mnc = cellInfo.cellIdentity.mncString
                }
                signalDbm = cellInfo.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_LTE
            }
            is CellInfoGsm -> {
                cellId = cellInfo.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }
                lac = cellInfo.cellIdentity.lac.takeIf { it != Int.MAX_VALUE }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mcc = cellInfo.cellIdentity.mccString
                    mnc = cellInfo.cellIdentity.mncString
                }
                signalDbm = cellInfo.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_GSM
            }
            is CellInfoWcdma -> {
                cellId = cellInfo.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }
                lac = cellInfo.cellIdentity.lac.takeIf { it != Int.MAX_VALUE }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mcc = cellInfo.cellIdentity.mccString
                    mnc = cellInfo.cellIdentity.mncString
                }
                signalDbm = cellInfo.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_UMTS
            }
            is CellInfoCdma -> {
                cellId = cellInfo.cellIdentity.basestationId
                signalDbm = cellInfo.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_CDMA
            }
        }
        
        // Handle NR (5G) for API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (cellInfo is CellInfoNr) {
                val nrIdentity = cellInfo.cellIdentity as? CellIdentityNr
                cellId = nrIdentity?.nci?.toInt()
                tac = nrIdentity?.tac
                mcc = nrIdentity?.mccString
                mnc = nrIdentity?.mncString
                signalDbm = (cellInfo.cellSignalStrength as? CellSignalStrengthNr)?.dbm ?: -100
                networkType = TelephonyManager.NETWORK_TYPE_NR
            }
        }
        
        return CellSnapshot(
            timestamp = System.currentTimeMillis(),
            cellId = cellId,
            lac = lac,
            tac = tac,
            mcc = mcc,
            mnc = mnc,
            signalStrength = signalDbm,
            networkType = networkType,
            latitude = currentLatitude,
            longitude = currentLongitude
        )
    }
    
    private fun analyzeForAnomalies(previous: CellSnapshot, current: CellSnapshot) {
        // 1. Check for encryption downgrade (4G/5G to 2G)
        if (isEncryptionDowngrade(previous.networkType, current.networkType)) {
            reportAnomaly(
                type = AnomalyType.ENCRYPTION_DOWNGRADE,
                description = "Network downgraded from ${getNetworkTypeName(previous.networkType)} to ${getNetworkTypeName(current.networkType)} - possible IMSI catcher forcing 2G",
                cellId = current.cellId,
                previousCellId = previous.cellId,
                signalStrength = current.signalStrength,
                previousSignalStrength = previous.signalStrength,
                networkType = getNetworkTypeName(current.networkType),
                previousNetworkType = getNetworkTypeName(previous.networkType),
                mccMnc = "${current.mcc}-${current.mnc}"
            )
        }
        
        // 2. Check for suspicious MCC/MNC
        val mccMnc = "${current.mcc}-${current.mnc}"
        if (mccMnc in SUSPICIOUS_MCC_MNC) {
            reportAnomaly(
                type = AnomalyType.SUSPICIOUS_NETWORK,
                description = "Connected to suspicious test network $mccMnc - likely IMSI catcher",
                cellId = current.cellId,
                previousCellId = previous.cellId,
                signalStrength = current.signalStrength,
                previousSignalStrength = previous.signalStrength,
                networkType = getNetworkTypeName(current.networkType),
                previousNetworkType = getNetworkTypeName(previous.networkType),
                mccMnc = mccMnc
            )
        }
        
        // 3. Check for unexpected cell tower change without movement
        if (current.cellId != previous.cellId && !hasMovedSignificantly(previous, current)) {
            reportAnomaly(
                type = AnomalyType.CELL_TOWER_CHANGE,
                description = "Cell tower changed (${previous.cellId} → ${current.cellId}) without significant movement",
                cellId = current.cellId,
                previousCellId = previous.cellId,
                signalStrength = current.signalStrength,
                previousSignalStrength = previous.signalStrength,
                networkType = getNetworkTypeName(current.networkType),
                previousNetworkType = getNetworkTypeName(previous.networkType),
                mccMnc = mccMnc
            )
        }
        
        // 4. Check for rapid cell switching
        val recentChanges = countRecentCellChanges(60_000) // Last minute
        if (recentChanges > 5) {
            reportAnomaly(
                type = AnomalyType.RAPID_CELL_SWITCHING,
                description = "Phone switching cells rapidly ($recentChanges times in last minute) - possible cell site simulator",
                cellId = current.cellId,
                previousCellId = previous.cellId,
                signalStrength = current.signalStrength,
                previousSignalStrength = previous.signalStrength,
                networkType = getNetworkTypeName(current.networkType),
                previousNetworkType = getNetworkTypeName(previous.networkType),
                mccMnc = mccMnc
            )
        }
        
        // 5. Check for LAC/TAC anomaly (unusual location area)
        if (hasLacTacAnomaly(previous, current)) {
            reportAnomaly(
                type = AnomalyType.LAC_TAC_ANOMALY,
                description = "Location area changed unexpectedly (LAC: ${previous.lac}→${current.lac}, TAC: ${previous.tac}→${current.tac})",
                cellId = current.cellId,
                previousCellId = previous.cellId,
                signalStrength = current.signalStrength,
                previousSignalStrength = previous.signalStrength,
                networkType = getNetworkTypeName(current.networkType),
                previousNetworkType = getNetworkTypeName(previous.networkType),
                mccMnc = mccMnc
            )
        }
    }
    
    private fun isEncryptionDowngrade(previousType: Int, currentType: Int): Boolean {
        val previousGen = getNetworkGeneration(previousType)
        val currentGen = getNetworkGeneration(currentType)
        // Downgrade to 2G is suspicious (2G has weak/no encryption)
        return previousGen > 2 && currentGen == 2
    }
    
    private fun getNetworkGeneration(networkType: Int): Int {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> 2
            
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> 3
            
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> 4
            
            TelephonyManager.NETWORK_TYPE_NR -> 5
            
            else -> 0
        }
    }
    
    private fun getNetworkTypeName(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA (2G)"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT (2G)"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN (2G)"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO 0 (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO A (3G)"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO B (3G)"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD (3G)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (4G)"
            TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
            else -> "Unknown"
        }
    }
    
    private fun hasMovedSignificantly(previous: CellSnapshot, current: CellSnapshot): Boolean {
        val prevLat = previous.latitude ?: return true // Assume moved if no location
        val prevLon = previous.longitude ?: return true
        val currLat = current.latitude ?: return true
        val currLon = current.longitude ?: return true
        
        // Calculate rough distance (simplified, not using haversine)
        val latDiff = kotlin.math.abs(currLat - prevLat)
        val lonDiff = kotlin.math.abs(currLon - prevLon)
        
        // About 100 meters threshold
        return latDiff > 0.001 || lonDiff > 0.001
    }
    
    private fun countRecentCellChanges(withinMs: Long): Int {
        val cutoff = System.currentTimeMillis() - withinMs
        val recentSnapshots = cellHistory.filter { it.timestamp > cutoff }
        
        if (recentSnapshots.size < 2) return 0
        
        var changes = 0
        for (i in 1 until recentSnapshots.size) {
            if (recentSnapshots[i].cellId != recentSnapshots[i - 1].cellId) {
                changes++
            }
        }
        return changes
    }
    
    private fun hasLacTacAnomaly(previous: CellSnapshot, current: CellSnapshot): Boolean {
        // LAC/TAC should not change without cell ID changing in most cases
        val lacChanged = previous.lac != null && current.lac != null && previous.lac != current.lac
        val tacChanged = previous.tac != null && current.tac != null && previous.tac != current.tac
        val cellSame = previous.cellId == current.cellId
        
        return (lacChanged || tacChanged) && cellSame
    }
    
    private fun reportAnomaly(
        type: AnomalyType,
        description: String,
        cellId: Int?,
        previousCellId: Int?,
        signalStrength: Int,
        previousSignalStrength: Int?,
        networkType: String,
        previousNetworkType: String?,
        mccMnc: String?
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastAnomalyTimes[type] ?: 0
        
        // Rate limit same anomaly type
        if (now - lastTime < MIN_ANOMALY_INTERVAL_MS) {
            return
        }
        lastAnomalyTimes[type] = now
        
        val severity = when (type.baseThreatScore) {
            in 90..100 -> ThreatLevel.CRITICAL
            in 70..89 -> ThreatLevel.HIGH
            in 50..69 -> ThreatLevel.MEDIUM
            in 30..49 -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }
        
        val anomaly = CellularAnomaly(
            type = type,
            severity = severity,
            description = description,
            cellId = cellId,
            previousCellId = previousCellId,
            signalStrength = signalStrength,
            previousSignalStrength = previousSignalStrength,
            networkType = networkType,
            previousNetworkType = previousNetworkType,
            mccMnc = mccMnc,
            latitude = currentLatitude,
            longitude = currentLongitude
        )
        
        detectedAnomalies.add(anomaly)
        _anomalies.value = detectedAnomalies.toList()
        
        Log.w(TAG, "CELLULAR ANOMALY DETECTED: ${type.displayName} - $description")
    }
    
    /**
     * Convert a cellular anomaly to a Detection for storage
     */
    fun anomalyToDetection(anomaly: CellularAnomaly): Detection {
        // Map anomaly type to detection method
        val detectionMethod = when (anomaly.type) {
            AnomalyType.ENCRYPTION_DOWNGRADE -> DetectionMethod.CELL_ENCRYPTION_DOWNGRADE
            AnomalyType.SUSPICIOUS_NETWORK -> DetectionMethod.CELL_SUSPICIOUS_NETWORK
            AnomalyType.CELL_TOWER_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            AnomalyType.RAPID_CELL_SWITCHING -> DetectionMethod.CELL_RAPID_SWITCHING
            AnomalyType.SIGNAL_SPIKE -> DetectionMethod.CELL_SIGNAL_ANOMALY
            AnomalyType.LAC_TAC_ANOMALY -> DetectionMethod.CELL_LAC_TAC_ANOMALY
            AnomalyType.UNKNOWN_CELL -> DetectionMethod.CELL_TOWER_CHANGE
        }
        
        // Build detailed device name
        val deviceName = when (anomaly.type) {
            AnomalyType.ENCRYPTION_DOWNGRADE -> 
                "Encryption Downgrade: ${anomaly.previousNetworkType ?: "4G/5G"} → ${anomaly.networkType}"
            AnomalyType.SUSPICIOUS_NETWORK -> 
                "Suspicious Network: ${anomaly.mccMnc ?: "Unknown"}"
            AnomalyType.CELL_TOWER_CHANGE -> 
                "Cell Change: ${anomaly.previousCellId ?: "?"} → ${anomaly.cellId ?: "?"}"
            AnomalyType.RAPID_CELL_SWITCHING -> 
                "Rapid Switching Detected"
            AnomalyType.SIGNAL_SPIKE -> 
                "Signal Spike: ${anomaly.previousSignalStrength ?: "?"}→${anomaly.signalStrength} dBm"
            AnomalyType.LAC_TAC_ANOMALY -> 
                "Location Area Changed Unexpectedly"
            AnomalyType.UNKNOWN_CELL -> 
                "Unknown Cell Tower: ${anomaly.cellId ?: "?"}"
        }
        
        // Build cell info JSON for serviceUuids field
        val cellInfo = buildString {
            append("{")
            append("\"cellId\":${anomaly.cellId ?: "null"},")
            append("\"prevCellId\":${anomaly.previousCellId ?: "null"},")
            append("\"networkType\":\"${anomaly.networkType}\",")
            append("\"prevNetworkType\":\"${anomaly.previousNetworkType ?: ""}\",")
            append("\"mccMnc\":\"${anomaly.mccMnc ?: ""}\",")
            append("\"signalDbm\":${anomaly.signalStrength},")
            append("\"prevSignalDbm\":${anomaly.previousSignalStrength ?: "null"}")
            append("}")
        }
        
        return Detection(
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = detectionMethod,
            deviceType = DeviceType.STINGRAY_IMSI,
            deviceName = deviceName,
            macAddress = anomaly.mccMnc, // Store MCC-MNC in MAC field
            ssid = "CELL-${anomaly.cellId ?: "UNK"}-${anomaly.id.take(8)}", // Unique identifier for DB
            rssi = anomaly.signalStrength,
            signalStrength = rssiToSignalStrength(anomaly.signalStrength),
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            threatLevel = anomaly.severity,
            threatScore = anomaly.type.baseThreatScore,
            manufacturer = anomaly.networkType, // Store network type (LTE, 5G, etc)
            firmwareVersion = "Cell ID: ${anomaly.cellId ?: "Unknown"}", // Store cell ID
            serviceUuids = cellInfo, // Store full cell info as JSON
            matchedPatterns = "[\"${anomaly.type.name}\"]"
        )
    }
}

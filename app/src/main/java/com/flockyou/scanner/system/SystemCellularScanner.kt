package com.flockyou.scanner.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.flockyou.scanner.CellularAnomaly
import com.flockyou.scanner.CellularAnomalyType
import com.flockyou.scanner.ICellularScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.Executor

/**
 * System/OEM privileged Cellular scanner.
 *
 * Capabilities:
 * - Real-time modem access (no rate limiting)
 * - IMEI/IMSI access with READ_PRIVILEGED_PHONE_STATE
 * - Full Cell Identity information (not zeroed)
 * - Enhanced IMSI catcher detection
 */
class SystemCellularScanner(
    private val context: Context
) : ICellularScanner {

    companion object {
        private const val TAG = "SystemCellScanner"

        // Privileged permissions
        private const val PERMISSION_READ_PRIVILEGED_PHONE_STATE = "android.permission.READ_PRIVILEGED_PHONE_STATE"
        private const val PERMISSION_MODIFY_PHONE_STATE = "android.permission.MODIFY_PHONE_STATE"
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    private val subscriptionManager: SubscriptionManager by lazy {
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainExecutor: Executor by lazy {
        ContextCompat.getMainExecutor(context)
    }

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    private val _cellInfo = MutableSharedFlow<List<CellInfo>>(replay = 1, extraBufferCapacity = 10)
    override val cellInfo: Flow<List<CellInfo>> = _cellInfo.asSharedFlow()

    private val _anomalies = MutableSharedFlow<List<CellularAnomaly>>(replay = 1, extraBufferCapacity = 10)
    override val anomalies: Flow<List<CellularAnomaly>> = _anomalies.asSharedFlow()

    private val detectedAnomalies = mutableListOf<CellularAnomaly>()
    private var lastCellInfo: List<CellInfo>? = null

    // Track cell history for anomaly detection
    private val cellHistory = mutableListOf<CellHistoryEntry>()
    private data class CellHistoryEntry(
        val timestamp: Long,
        val cellId: String,
        val lac: Int?,
        val signalStrength: Int?
    )

    // For Android S+ (API 31)
    private var telephonyCallback: TelephonyCallback? = null

    // For pre-Android S
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // Cached IMEI/IMSI for performance
    private var cachedImei: String? = null
    private var cachedImsi: String? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Scanner already active")
            return true
        }

        try {
            registerCellInfoListener()
            _isActive.value = true
            _lastError.value = null

            // Get initial cell info with real-time request
            requestCellInfoUpdate()

            // Pre-cache IMEI/IMSI if we have privileged access
            if (hasPrivilegedAccess()) {
                cacheDeviceIdentifiers()
            }

            Log.d(TAG, "System Cellular scanner started (privileged: ${hasPrivilegedAccess()})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cellular scanner", e)
            _lastError.value = "Failed to start: ${e.message}"
            return false
        }
    }

    override fun stop() {
        unregisterCellInfoListener()
        _isActive.value = false
        Log.d(TAG, "System Cellular scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean {
        // System apps may have permissions pre-granted
        return !hasAllPermissionsGranted()
    }

    override fun getRequiredPermissions(): List<String> {
        return listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    @SuppressLint("MissingPermission")
    override fun requestCellInfoUpdate() {
        if (!_isActive.value) {
            Log.w(TAG, "Scanner not active")
            return
        }

        try {
            // System apps get real-time updates without rate limiting
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        handleCellInfoUpdate(cellInfo)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        Log.e(TAG, "Cell info request error: $errorCode", detail)
                        _lastError.value = "Cell info error: $errorCode"
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                val cellInfo = telephonyManager.allCellInfo
                if (cellInfo != null) {
                    handleCellInfoUpdate(cellInfo)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for cell info", e)
            _lastError.value = "Permission denied: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    override fun getImei(slotIndex: Int): String? {
        if (!hasPrivilegedAccess()) {
            Log.w(TAG, "IMEI access requires READ_PRIVILEGED_PHONE_STATE")
            return null
        }

        cachedImei?.let { return it }

        return try {
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.getImei(slotIndex)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.deviceId
            }
            cachedImei = imei
            imei
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for IMEI", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IMEI", e)
            null
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    override fun getImsi(): String? {
        if (!hasPrivilegedAccess()) {
            Log.w(TAG, "IMSI access requires READ_PRIVILEGED_PHONE_STATE")
            return null
        }

        cachedImsi?.let { return it }

        return try {
            val imsi = telephonyManager.subscriberId
            cachedImsi = imsi
            imsi
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for IMSI", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IMSI", e)
            null
        }
    }

    override fun hasPrivilegedAccess(): Boolean {
        return hasPermission(PERMISSION_READ_PRIVILEGED_PHONE_STATE)
    }

    /**
     * Get the MEID for CDMA devices.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getMeid(slotIndex: Int = 0): String? {
        if (!hasPrivilegedAccess()) return null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.getMeid(slotIndex)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting MEID", e)
            null
        }
    }

    /**
     * Get the SIM serial number (ICCID).
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getSimSerialNumber(): String? {
        if (!hasPrivilegedAccess()) return null

        return try {
            telephonyManager.simSerialNumber
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SIM serial", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerCellInfoListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallbackS()
        } else {
            registerPhoneStateListener()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallbackS() {
        telephonyCallback = object : TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.ServiceStateListener {

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                handleCellInfoUpdate(cellInfo)
            }

            override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
                handleServiceStateChange(serviceState)
            }
        }

        try {
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback!!)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register telephony callback", e)
            _lastError.value = "Permission denied: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                cellInfo?.let { handleCellInfoUpdate(it) }
            }

            @Deprecated("Deprecated in Java")
            override fun onServiceStateChanged(serviceState: android.telephony.ServiceState?) {
                serviceState?.let { handleServiceStateChange(it) }
            }
        }

        try {
            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SERVICE_STATE
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register phone state listener", e)
            _lastError.value = "Permission denied: ${e.message}"
        }
    }

    private fun unregisterCellInfoListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                try {
                    telephonyManager.unregisterTelephonyCallback(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering telephony callback", e)
                }
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                try {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering phone state listener", e)
                }
            }
            phoneStateListener = null
        }
    }

    private fun handleCellInfoUpdate(cellInfo: List<CellInfo>) {
        lastCellInfo = cellInfo

        scope.launch {
            _cellInfo.emit(cellInfo)
        }

        // Enhanced anomaly detection with privileged access
        analyzeForAnomalies(cellInfo)

        Log.d(TAG, "Cell info updated: ${cellInfo.size} cells")
    }

    private fun handleServiceStateChange(serviceState: android.telephony.ServiceState) {
        // Check for potential network downgrade attacks
        val state = serviceState.state
        val roaming = serviceState.roaming

        if (roaming) {
            val anomaly = CellularAnomaly(
                type = CellularAnomalyType.UNEXPECTED_TOWER_CHANGE,
                description = "Device now roaming - potential cell tower manipulation",
                cellId = null,
                lac = null,
                mcc = null,
                mnc = null,
                signalStrength = null,
                metadata = mapOf("roaming" to "true", "state" to state.toString())
            )
            addAnomaly(anomaly)
        }
    }

    private fun analyzeForAnomalies(cellInfoList: List<CellInfo>) {
        val newAnomalies = mutableListOf<CellularAnomaly>()

        for (cellInfo in cellInfoList) {
            when {
                cellInfo is CellInfoLte -> analyzeLteCell(cellInfo, newAnomalies)
                cellInfo is CellInfoGsm -> analyzeGsmCell(cellInfo, newAnomalies)
                cellInfo is CellInfoWcdma -> analyzeWcdmaCell(cellInfo, newAnomalies)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> {
                    analyzeNrCell(cellInfo, newAnomalies)
                }
            }
        }

        // Enhanced checks with privileged access
        if (hasPrivilegedAccess()) {
            checkForImsiCatcher(cellInfoList, newAnomalies)
        }

        // Check for rapid tower changes
        checkForRapidTowerChanges(cellInfoList, newAnomalies)

        if (newAnomalies.isNotEmpty()) {
            newAnomalies.forEach { addAnomaly(it) }
        }
    }

    private fun analyzeLteCell(cellInfo: CellInfoLte, anomalies: MutableList<CellularAnomaly>) {
        val identity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength.dbm
        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mccString?.toIntOrNull() else null
        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mncString?.toIntOrNull() else null

        // Update cell history
        updateCellHistory(identity.ci.toString(), identity.tac, signalStrength)

        // Check for abnormally strong signal (potential fake base station)
        if (signalStrength > -50) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.SIGNAL_TOO_STRONG,
                    description = "LTE signal unusually strong: $signalStrength dBm (potential IMSI catcher)",
                    cellId = identity.ci.toString(),
                    lac = identity.tac,
                    mcc = mcc,
                    mnc = mnc,
                    signalStrength = signalStrength
                )
            )
        }

        // Check for invalid/suspicious cell ID
        if (identity.ci == Int.MAX_VALUE || identity.ci <= 0) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.FAKE_BASE_STATION,
                    description = "Invalid cell ID detected: ${identity.ci}",
                    cellId = identity.ci.toString(),
                    lac = identity.tac,
                    mcc = mcc,
                    mnc = mnc,
                    signalStrength = signalStrength
                )
            )
        }
    }

    private fun analyzeGsmCell(cellInfo: CellInfoGsm, anomalies: MutableList<CellularAnomaly>) {
        val identity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength.dbm
        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mccString?.toIntOrNull() else null
        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mncString?.toIntOrNull() else null

        updateCellHistory(identity.cid.toString(), identity.lac, signalStrength)

        // GSM (2G) is inherently less secure - flag if primary
        if (cellInfo.isRegistered) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.PROTOCOL_DOWNGRADE,
                    description = "Connected to 2G GSM network - vulnerable to interception",
                    cellId = identity.cid.toString(),
                    lac = identity.lac,
                    mcc = mcc,
                    mnc = mnc,
                    signalStrength = signalStrength,
                    metadata = mapOf("network_type" to "GSM")
                )
            )
        }

        // Strong signal check
        if (signalStrength > -50) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.SIGNAL_TOO_STRONG,
                    description = "GSM signal unusually strong: $signalStrength dBm",
                    cellId = identity.cid.toString(),
                    lac = identity.lac,
                    mcc = mcc,
                    mnc = mnc,
                    signalStrength = signalStrength
                )
            )
        }
    }

    private fun analyzeWcdmaCell(cellInfo: CellInfoWcdma, anomalies: MutableList<CellularAnomaly>) {
        val identity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength.dbm
        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mccString?.toIntOrNull() else null
        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mncString?.toIntOrNull() else null

        updateCellHistory(identity.cid.toString(), identity.lac, signalStrength)

        if (signalStrength > -50) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.SIGNAL_TOO_STRONG,
                    description = "WCDMA signal unusually strong: $signalStrength dBm",
                    cellId = identity.cid.toString(),
                    lac = identity.lac,
                    mcc = mcc,
                    mnc = mnc,
                    signalStrength = signalStrength
                )
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun analyzeNrCell(cellInfo: CellInfoNr, anomalies: MutableList<CellularAnomaly>) {
        val identity = cellInfo.cellIdentity as? android.telephony.CellIdentityNr ?: return
        val signalStrength = cellInfo.cellSignalStrength.dbm

        updateCellHistory(identity.nci.toString(), identity.tac, signalStrength)

        if (signalStrength > -50) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.SIGNAL_TOO_STRONG,
                    description = "5G NR signal unusually strong: $signalStrength dBm",
                    cellId = identity.nci.toString(),
                    lac = identity.tac,
                    mcc = identity.mccString?.toIntOrNull(),
                    mnc = identity.mncString?.toIntOrNull(),
                    signalStrength = signalStrength
                )
            )
        }
    }

    /**
     * Enhanced IMSI catcher detection with privileged access.
     */
    private fun checkForImsiCatcher(cellInfoList: List<CellInfo>, anomalies: MutableList<CellularAnomaly>) {
        // With privileged access, we can do more sophisticated analysis
        val registeredCells = cellInfoList.filter { it.isRegistered }

        for (cell in registeredCells) {
            // Check for null encryption (A5/0)
            // This would require additional modem API access

            // Check for location area code changes
            val currentLac = when (cell) {
                is CellInfoLte -> cell.cellIdentity.tac
                is CellInfoGsm -> cell.cellIdentity.lac
                is CellInfoWcdma -> cell.cellIdentity.lac
                else -> null
            }

            if (currentLac != null && cellHistory.isNotEmpty()) {
                val recentLacs = cellHistory.takeLast(10).mapNotNull { it.lac }.distinct()
                if (recentLacs.size > 3) {
                    anomalies.add(
                        CellularAnomaly(
                            type = CellularAnomalyType.UNUSUAL_LAC,
                            description = "Frequent LAC changes detected: ${recentLacs.size} different LACs",
                            cellId = null,
                            lac = currentLac,
                            mcc = null,
                            mnc = null,
                            signalStrength = null,
                            metadata = mapOf("lacs" to recentLacs.joinToString(","))
                        )
                    )
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun checkForRapidTowerChanges(cellInfoList: List<CellInfo>, anomalies: MutableList<CellularAnomaly>) {
        if (cellHistory.size < 5) return

        val recentHistory = cellHistory.takeLast(10)
        val uniqueCells = recentHistory.map { it.cellId }.distinct()

        // If we've seen 5+ different cells in recent history, that's suspicious
        if (uniqueCells.size >= 5) {
            val timeSpan = recentHistory.last().timestamp - recentHistory.first().timestamp
            if (timeSpan < 60000) { // Within 1 minute
                anomalies.add(
                    CellularAnomaly(
                        type = CellularAnomalyType.IMSI_CATCHER_SUSPECTED,
                        description = "Rapid cell tower changes: ${uniqueCells.size} towers in ${timeSpan / 1000}s",
                        cellId = null,
                        lac = null,
                        mcc = null,
                        mnc = null,
                        signalStrength = null,
                        metadata = mapOf(
                            "cell_count" to uniqueCells.size.toString(),
                            "time_span_ms" to timeSpan.toString()
                        )
                    )
                )
            }
        }
    }

    private fun updateCellHistory(cellId: String, lac: Int?, signalStrength: Int?) {
        cellHistory.add(
            CellHistoryEntry(
                timestamp = System.currentTimeMillis(),
                cellId = cellId,
                lac = lac,
                signalStrength = signalStrength
            )
        )

        // Keep only last 100 entries
        while (cellHistory.size > 100) {
            cellHistory.removeAt(0)
        }
    }

    private fun addAnomaly(anomaly: CellularAnomaly) {
        detectedAnomalies.add(anomaly)
        while (detectedAnomalies.size > 100) {
            detectedAnomalies.removeAt(0)
        }
        scope.launch {
            _anomalies.emit(detectedAnomalies.toList())
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun cacheDeviceIdentifiers() {
        try {
            cachedImei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.imei
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.deviceId
            }
            cachedImsi = telephonyManager.subscriberId
            Log.d(TAG, "Device identifiers cached")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache device identifiers", e)
        }
    }

    private fun hasAllPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { hasPermission(it) }
    }

    private fun hasPermission(permission: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}

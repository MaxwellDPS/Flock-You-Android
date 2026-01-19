package com.flockyou.scanner.standard

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
@Suppress("DEPRECATION")
import android.telephony.PhoneStateListener
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
import java.util.concurrent.Executor

/**
 * Standard Cellular scanner for sideloaded apps.
 *
 * Limitations:
 * - Cell info updates rate-limited (~10s between updates)
 * - Some Cell Identity fields may be zeroed
 * - No IMEI/IMSI access without privileged permissions
 */
class StandardCellularScanner(
    private val context: Context
) : ICellularScanner {

    companion object {
        private const val TAG = "StandardCellScanner"
        private const val MIN_UPDATE_INTERVAL_MS = 10000L
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
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
    private var lastUpdateTime = 0L

    // For Android S+ (API 31)
    private var telephonyCallback: TelephonyCallback? = null

    // For pre-Android S
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Scanner already active")
            return true
        }

        if (!hasRequiredPermissions()) {
            _lastError.value = "Required permissions not granted"
            Log.e(TAG, "Cannot start: permissions not granted")
            return false
        }

        try {
            registerCellInfoListener()
            _isActive.value = true
            _lastError.value = null

            // Get initial cell info
            requestCellInfoUpdate()

            Log.d(TAG, "Standard Cellular scanner started")
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
        Log.d(TAG, "Standard Cellular scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean = true

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

        // Rate limiting check
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
            Log.d(TAG, "Rate limited, using cached cell info")
            lastCellInfo?.let { cached ->
                scope.launch { _cellInfo.emit(cached) }
            }
            return
        }

        try {
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
                // Fallback for older versions
                @Suppress("DEPRECATION")
                val cellInfo = telephonyManager.allCellInfo
                if (cellInfo != null) {
                    handleCellInfoUpdate(cellInfo)
                }
            }
            lastUpdateTime = now
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for cell info", e)
            _lastError.value = "Permission denied: ${e.message}"
        }
    }

    override fun getImei(slotIndex: Int): String? {
        // Not available in standard mode
        Log.w(TAG, "IMEI access requires privileged permissions")
        return null
    }

    override fun getImsi(): String? {
        // Not available in standard mode
        Log.w(TAG, "IMSI access requires privileged permissions")
        return null
    }

    override fun hasPrivilegedAccess(): Boolean = false

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
        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                handleCellInfoUpdate(cellInfo)
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
        }

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CELL_INFO)
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
        lastUpdateTime = System.currentTimeMillis()

        scope.launch {
            _cellInfo.emit(cellInfo)
        }

        // Check for anomalies
        analyzeForAnomalies(cellInfo)

        Log.d(TAG, "Cell info updated: ${cellInfo.size} cells")
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

        // Check for cell tower changes
        checkForTowerChanges(cellInfoList, newAnomalies)

        if (newAnomalies.isNotEmpty()) {
            detectedAnomalies.addAll(newAnomalies)
            // Keep only last 100 anomalies
            while (detectedAnomalies.size > 100) {
                detectedAnomalies.removeAt(0)
            }
            scope.launch {
                _anomalies.emit(detectedAnomalies.toList())
            }
        }
    }

    private fun analyzeLteCell(cellInfo: CellInfoLte, anomalies: MutableList<CellularAnomaly>) {
        val identity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength.dbm
        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mccString?.toIntOrNull() else null
        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.mncString?.toIntOrNull() else null

        // Check for abnormally strong signal (potential fake base station)
        if (signalStrength > -50) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.SIGNAL_TOO_STRONG,
                    description = "LTE signal unusually strong: $signalStrength dBm",
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

        // GSM is older and less secure - flag if primary network
        if (cellInfo.isRegistered) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.PROTOCOL_DOWNGRADE,
                    description = "Connected to 2G GSM network (lower security)",
                    cellId = identity.cid.toString(),
                    lac = identity.lac,
                    mcc = mcc,
                    mnc = mnc,
                    signalStrength = signalStrength
                )
            )
        }

        // Check for abnormally strong signal
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

        // Check for abnormally strong signal
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

        // 5G NR is generally more secure, but still check for anomalies
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

    private fun checkForTowerChanges(currentCells: List<CellInfo>, anomalies: MutableList<CellularAnomaly>) {
        val previousCells = lastCellInfo ?: return

        // Find registered cells
        val previousRegistered = previousCells.filter { it.isRegistered }
        val currentRegistered = currentCells.filter { it.isRegistered }

        if (previousRegistered.isEmpty() || currentRegistered.isEmpty()) return

        // Check if the serving cell changed unexpectedly
        val prevId = getCellId(previousRegistered.first())
        val currId = getCellId(currentRegistered.first())

        if (prevId != null && currId != null && prevId != currId) {
            anomalies.add(
                CellularAnomaly(
                    type = CellularAnomalyType.UNEXPECTED_TOWER_CHANGE,
                    description = "Serving cell changed from $prevId to $currId",
                    cellId = currId,
                    lac = null,
                    mcc = null,
                    mnc = null,
                    signalStrength = null
                )
            )
        }
    }

    private fun getCellId(cellInfo: CellInfo): String? {
        return when {
            cellInfo is CellInfoLte -> cellInfo.cellIdentity.ci.toString()
            cellInfo is CellInfoGsm -> cellInfo.cellIdentity.cid.toString()
            cellInfo is CellInfoWcdma -> cellInfo.cellIdentity.cid.toString()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> {
                (cellInfo.cellIdentity as? android.telephony.CellIdentityNr)?.nci?.toString()
            }
            else -> null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}

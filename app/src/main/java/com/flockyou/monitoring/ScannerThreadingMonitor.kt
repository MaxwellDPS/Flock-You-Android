package com.flockyou.monitoring

import android.os.Debug
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive Scanner Threading Monitor
 *
 * Tracks thread states, coroutine health, execution metrics, and resource usage
 * across all scanners and detectors in the scanning service.
 *
 * Provides real-time visibility into:
 * - Active threads and coroutines per scanner
 * - Execution times and throughput
 * - Queue depths and backpressure
 * - Error rates and failure patterns
 * - Memory and CPU usage approximations
 * - IPC message latency
 */
@Singleton
class ScannerThreadingMonitor @Inject constructor() {

    companion object {
        private const val TAG = "ThreadingMonitor"
        private const val METRICS_WINDOW_MS = 60_000L // 1 minute rolling window
        private const val SNAPSHOT_INTERVAL_MS = 1_000L // Update every second
        private const val MAX_EXECUTION_HISTORY = 100
        private const val MAX_ERROR_HISTORY = 50

        // Scanner/Detector identifiers
        const val SCANNER_BLE = "ble_scanner"
        const val SCANNER_WIFI = "wifi_scanner"
        const val SCANNER_CELLULAR = "cellular_scanner"
        const val DETECTOR_CELLULAR = "cellular_detector"
        const val DETECTOR_SATELLITE = "satellite_detector"
        const val DETECTOR_ROGUE_WIFI = "rogue_wifi_detector"
        const val DETECTOR_RF = "rf_detector"
        const val DETECTOR_ULTRASONIC = "ultrasonic_detector"
        const val DETECTOR_GNSS = "gnss_detector"
        const val IPC_HANDLER = "ipc_handler"
        const val HEALTH_CHECKER = "health_checker"
    }

    // Monitor scope
    private val monitorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var snapshotJob: Job? = null

    // Thread metrics per scanner
    private val threadMetrics = ConcurrentHashMap<String, ThreadMetrics>()

    // Execution tracking
    private val executionTracker = ConcurrentHashMap<String, ExecutionTracker>()

    // Coroutine job tracking
    private val activeJobs = ConcurrentHashMap<String, MutableSet<JobInfo>>()

    // IPC metrics
    private val ipcMetrics = IpcMetrics()

    // Overall system state
    private val _systemState = MutableStateFlow(SystemThreadingState())
    val systemState: StateFlow<SystemThreadingState> = _systemState.asStateFlow()

    // Per-scanner states
    private val _scannerStates = MutableStateFlow<Map<String, ScannerThreadState>>(emptyMap())
    val scannerStates: StateFlow<Map<String, ScannerThreadState>> = _scannerStates.asStateFlow()

    // Threading alerts
    private val _threadingAlerts = MutableStateFlow<List<ThreadingAlert>>(emptyList())
    val threadingAlerts: StateFlow<List<ThreadingAlert>> = _threadingAlerts.asStateFlow()

    init {
        // Initialize metrics for all known scanners
        listOf(
            SCANNER_BLE, SCANNER_WIFI, SCANNER_CELLULAR,
            DETECTOR_CELLULAR, DETECTOR_SATELLITE, DETECTOR_ROGUE_WIFI,
            DETECTOR_RF, DETECTOR_ULTRASONIC, DETECTOR_GNSS,
            IPC_HANDLER, HEALTH_CHECKER
        ).forEach { scanner ->
            threadMetrics[scanner] = ThreadMetrics(scanner)
            executionTracker[scanner] = ExecutionTracker(scanner)
            activeJobs[scanner] = ConcurrentHashMap.newKeySet()
        }
    }

    /**
     * Start monitoring - call when scanning service starts
     */
    fun startMonitoring() {
        Log.i(TAG, "Starting scanner threading monitor")
        snapshotJob?.cancel()
        snapshotJob = monitorScope.launch {
            while (isActive) {
                captureSnapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop monitoring - call when scanning service stops
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping scanner threading monitor")
        snapshotJob?.cancel()
        snapshotJob = null
    }

    // ========== Execution Tracking API ==========

    /**
     * Record start of a scan/detection cycle
     * @return execution ID for tracking
     */
    fun recordExecutionStart(scannerId: String): Long {
        val tracker = executionTracker[scannerId] ?: return -1
        return tracker.startExecution()
    }

    /**
     * Record end of a scan/detection cycle
     */
    fun recordExecutionEnd(scannerId: String, executionId: Long, success: Boolean, itemsProcessed: Int = 0) {
        val tracker = executionTracker[scannerId] ?: return
        tracker.endExecution(executionId, success, itemsProcessed)
    }

    /**
     * Record a scan error
     */
    fun recordError(scannerId: String, errorType: String, message: String) {
        val tracker = executionTracker[scannerId] ?: return
        tracker.recordError(errorType, message)
    }

    // ========== Job Tracking API ==========

    /**
     * Register an active coroutine job
     */
    fun registerJob(scannerId: String, jobName: String, job: Job): String {
        val jobId = "${scannerId}_${jobName}_${System.nanoTime()}"
        val jobInfo = JobInfo(
            id = jobId,
            name = jobName,
            startTime = System.currentTimeMillis(),
            job = job
        )
        activeJobs[scannerId]?.add(jobInfo)

        // Auto-remove when job completes
        monitorScope.launch {
            job.join()
            activeJobs[scannerId]?.removeIf { it.id == jobId }
        }

        return jobId
    }

    /**
     * Unregister a job
     */
    fun unregisterJob(scannerId: String, jobId: String) {
        activeJobs[scannerId]?.removeIf { it.id == jobId }
    }

    // ========== Thread Metrics API ==========

    /**
     * Record thread creation
     */
    fun recordThreadStart(scannerId: String, threadName: String) {
        threadMetrics[scannerId]?.recordThreadStart(threadName)
    }

    /**
     * Record thread termination
     */
    fun recordThreadEnd(scannerId: String, threadName: String) {
        threadMetrics[scannerId]?.recordThreadEnd(threadName)
    }

    /**
     * Update queue depth for a scanner
     */
    fun updateQueueDepth(scannerId: String, depth: Int) {
        threadMetrics[scannerId]?.updateQueueDepth(depth)
    }

    // ========== IPC Tracking API ==========

    /**
     * Record IPC message sent
     */
    fun recordIpcMessageSent(messageType: Int) {
        ipcMetrics.recordMessageSent(messageType)
    }

    /**
     * Record IPC message received with latency
     */
    fun recordIpcMessageReceived(messageType: Int, latencyMs: Long) {
        ipcMetrics.recordMessageReceived(messageType, latencyMs)
    }

    /**
     * Update IPC client count
     */
    fun updateIpcClientCount(count: Int) {
        ipcMetrics.updateClientCount(count)
    }

    // ========== Snapshot Capture ==========

    private fun captureSnapshot() {
        try {
            val now = System.currentTimeMillis()

            // Capture per-scanner states
            val states = threadMetrics.mapValues { (scannerId, metrics) ->
                val tracker = executionTracker[scannerId]
                val jobs = activeJobs[scannerId] ?: emptySet()

                ScannerThreadState(
                    scannerId = scannerId,
                    activeThreadCount = metrics.getActiveThreadCount(),
                    activeJobCount = jobs.count { it.job.isActive },
                    totalJobsStarted = jobs.size,
                    currentQueueDepth = metrics.getCurrentQueueDepth(),
                    maxQueueDepth = metrics.getMaxQueueDepth(),
                    avgExecutionTimeMs = tracker?.getAverageExecutionTime() ?: 0.0,
                    maxExecutionTimeMs = tracker?.getMaxExecutionTime() ?: 0L,
                    minExecutionTimeMs = tracker?.getMinExecutionTime() ?: 0L,
                    executionsPerMinute = tracker?.getExecutionsPerMinute() ?: 0.0,
                    successRate = tracker?.getSuccessRate() ?: 1.0,
                    totalExecutions = tracker?.getTotalExecutions() ?: 0L,
                    totalSuccesses = tracker?.getTotalSuccesses() ?: 0L,
                    totalFailures = tracker?.getTotalFailures() ?: 0L,
                    totalItemsProcessed = tracker?.getTotalItemsProcessed() ?: 0L,
                    lastExecutionTime = tracker?.getLastExecutionTime() ?: 0L,
                    lastErrorTime = tracker?.getLastErrorTime() ?: 0L,
                    lastErrorMessage = tracker?.getLastErrorMessage(),
                    recentErrors = tracker?.getRecentErrors() ?: emptyList(),
                    isStalled = isStalled(scannerId, tracker),
                    stalledDurationMs = getStalledDuration(scannerId, tracker),
                    threadNames = metrics.getActiveThreadNames()
                )
            }

            _scannerStates.value = states

            // Calculate system-wide state
            val totalActiveThreads = states.values.sumOf { it.activeThreadCount }
            val totalActiveJobs = states.values.sumOf { it.activeJobCount }
            val avgSuccessRate = if (states.isNotEmpty()) {
                states.values.map { it.successRate }.average()
            } else 1.0

            val systemMemory = getSystemMemoryInfo()
            val processThreadCount = getProcessThreadCount()

            _systemState.update {
                SystemThreadingState(
                    timestamp = now,
                    totalActiveThreads = totalActiveThreads,
                    totalActiveJobs = totalActiveJobs,
                    processThreadCount = processThreadCount,
                    averageSuccessRate = avgSuccessRate,
                    totalExecutionsPerMinute = states.values.sumOf { it.executionsPerMinute },
                    heapUsedMb = systemMemory.heapUsedMb,
                    heapMaxMb = systemMemory.heapMaxMb,
                    nativeHeapMb = systemMemory.nativeHeapMb,
                    ipcMessagesSentPerMinute = ipcMetrics.getMessagesSentPerMinute(),
                    ipcMessagesReceivedPerMinute = ipcMetrics.getMessagesReceivedPerMinute(),
                    ipcAverageLatencyMs = ipcMetrics.getAverageLatency(),
                    ipcConnectedClients = ipcMetrics.getClientCount(),
                    stalledScanners = states.values.filter { it.isStalled }.map { it.scannerId },
                    healthScore = calculateHealthScore(states, avgSuccessRate)
                )
            }

            // Generate alerts
            generateAlerts(states)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing threading snapshot", e)
        }
    }

    private fun isStalled(scannerId: String, tracker: ExecutionTracker?): Boolean {
        if (tracker == null) return false
        val lastExecution = tracker.getLastExecutionTime()
        if (lastExecution == 0L) return false

        // Consider stalled if no activity for 2 minutes (except for some detectors)
        val stalledThreshold = when (scannerId) {
            SCANNER_WIFI -> 180_000L // WiFi has throttling, 3 min
            DETECTOR_SATELLITE -> 300_000L // Satellite checks less frequently, 5 min
            else -> 120_000L // Default 2 min
        }

        return System.currentTimeMillis() - lastExecution > stalledThreshold
    }

    private fun getStalledDuration(scannerId: String, tracker: ExecutionTracker?): Long {
        if (tracker == null) return 0L
        val lastExecution = tracker.getLastExecutionTime()
        if (lastExecution == 0L) return 0L
        return System.currentTimeMillis() - lastExecution
    }

    private fun calculateHealthScore(states: Map<String, ScannerThreadState>, avgSuccessRate: Double): Float {
        var score = 100f

        // Deduct for stalled scanners
        val stalledCount = states.values.count { it.isStalled }
        score -= stalledCount * 15f

        // Deduct for low success rate
        score -= ((1.0 - avgSuccessRate) * 30).toFloat()

        // Deduct for high queue depths
        val highQueueCount = states.values.count { it.currentQueueDepth > 100 }
        score -= highQueueCount * 5f

        // Deduct for recent errors
        val recentErrorCount = states.values.sumOf { it.recentErrors.size }
        score -= (recentErrorCount * 2f).coerceAtMost(20f)

        return score.coerceIn(0f, 100f)
    }

    private fun generateAlerts(states: Map<String, ScannerThreadState>) {
        val alerts = mutableListOf<ThreadingAlert>()

        states.forEach { (scannerId, state) ->
            // Stalled scanner alert
            if (state.isStalled) {
                alerts.add(ThreadingAlert(
                    severity = AlertSeverity.WARNING,
                    scannerId = scannerId,
                    title = "Scanner Stalled",
                    message = "${formatScannerId(scannerId)} has not executed for ${formatDuration(state.stalledDurationMs)}",
                    timestamp = System.currentTimeMillis()
                ))
            }

            // Low success rate alert
            if (state.successRate < 0.8 && state.totalExecutions > 10) {
                alerts.add(ThreadingAlert(
                    severity = AlertSeverity.WARNING,
                    scannerId = scannerId,
                    title = "Low Success Rate",
                    message = "${formatScannerId(scannerId)} success rate is ${(state.successRate * 100).toInt()}%",
                    timestamp = System.currentTimeMillis()
                ))
            }

            // High queue depth alert
            if (state.currentQueueDepth > 500) {
                alerts.add(ThreadingAlert(
                    severity = AlertSeverity.ERROR,
                    scannerId = scannerId,
                    title = "High Queue Backlog",
                    message = "${formatScannerId(scannerId)} has ${state.currentQueueDepth} items queued",
                    timestamp = System.currentTimeMillis()
                ))
            }

            // Slow execution alert
            if (state.avgExecutionTimeMs > 5000) {
                alerts.add(ThreadingAlert(
                    severity = AlertSeverity.INFO,
                    scannerId = scannerId,
                    title = "Slow Execution",
                    message = "${formatScannerId(scannerId)} avg execution time is ${state.avgExecutionTimeMs.toLong()}ms",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }

        _threadingAlerts.value = alerts.take(20) // Keep only recent alerts
    }

    private fun formatScannerId(id: String): String = id.replace("_", " ")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "${ms / 1000}s"
            ms < 3600_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
            else -> "${ms / 3600_000}h ${(ms % 3600_000) / 60_000}m"
        }
    }

    private fun getSystemMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val heapMax = runtime.maxMemory() / (1024 * 1024)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        return MemoryInfo(
            heapUsedMb = heapUsed,
            heapMaxMb = heapMax,
            nativeHeapMb = nativeHeap
        )
    }

    private fun getProcessThreadCount(): Int {
        return try {
            Thread.getAllStackTraces().size
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Get a detailed report for debugging
     */
    fun getDetailedReport(): String {
        val sb = StringBuilder()
        val state = _systemState.value
        val scanners = _scannerStates.value

        sb.appendLine("=== Scanner Threading Monitor Report ===")
        sb.appendLine("Timestamp: ${java.util.Date(state.timestamp)}")
        sb.appendLine()
        sb.appendLine("== System Overview ==")
        sb.appendLine("Health Score: ${state.healthScore}%")
        sb.appendLine("Process Threads: ${state.processThreadCount}")
        sb.appendLine("Active Scanner Threads: ${state.totalActiveThreads}")
        sb.appendLine("Active Coroutine Jobs: ${state.totalActiveJobs}")
        sb.appendLine("Avg Success Rate: ${(state.averageSuccessRate * 100).toInt()}%")
        sb.appendLine("Total Executions/min: ${state.totalExecutionsPerMinute.toInt()}")
        sb.appendLine()
        sb.appendLine("== Memory ==")
        sb.appendLine("Heap: ${state.heapUsedMb}MB / ${state.heapMaxMb}MB")
        sb.appendLine("Native Heap: ${state.nativeHeapMb}MB")
        sb.appendLine()
        sb.appendLine("== IPC ==")
        sb.appendLine("Connected Clients: ${state.ipcConnectedClients}")
        sb.appendLine("Messages Sent/min: ${state.ipcMessagesSentPerMinute.toInt()}")
        sb.appendLine("Messages Recv/min: ${state.ipcMessagesReceivedPerMinute.toInt()}")
        sb.appendLine("Avg Latency: ${state.ipcAverageLatencyMs}ms")
        sb.appendLine()
        sb.appendLine("== Per-Scanner Details ==")

        scanners.forEach { (id, scanner) ->
            sb.appendLine()
            sb.appendLine("--- ${formatScannerId(id)} ---")
            sb.appendLine("  Active Threads: ${scanner.activeThreadCount}")
            sb.appendLine("  Active Jobs: ${scanner.activeJobCount}")
            sb.appendLine("  Queue Depth: ${scanner.currentQueueDepth} (max: ${scanner.maxQueueDepth})")
            sb.appendLine("  Executions: ${scanner.totalExecutions} (${scanner.executionsPerMinute.toInt()}/min)")
            sb.appendLine("  Success Rate: ${(scanner.successRate * 100).toInt()}%")
            sb.appendLine("  Avg Exec Time: ${scanner.avgExecutionTimeMs.toLong()}ms")
            sb.appendLine("  Items Processed: ${scanner.totalItemsProcessed}")
            sb.appendLine("  Stalled: ${scanner.isStalled}")
            if (scanner.lastErrorMessage != null) {
                sb.appendLine("  Last Error: ${scanner.lastErrorMessage}")
            }
        }

        if (state.stalledScanners.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("== STALLED SCANNERS ==")
            state.stalledScanners.forEach { sb.appendLine("  - $it") }
        }

        return sb.toString()
    }

    // ========== Inner Classes ==========

    /**
     * Thread metrics for a single scanner
     */
    private class ThreadMetrics(val scannerId: String) {
        private val activeThreads = ConcurrentHashMap<String, Long>()
        private val queueDepth = AtomicInteger(0)
        private val maxQueueDepth = AtomicInteger(0)

        fun recordThreadStart(threadName: String) {
            activeThreads[threadName] = System.currentTimeMillis()
        }

        fun recordThreadEnd(threadName: String) {
            activeThreads.remove(threadName)
        }

        fun updateQueueDepth(depth: Int) {
            queueDepth.set(depth)
            maxQueueDepth.updateAndGet { max -> maxOf(max, depth) }
        }

        fun getActiveThreadCount() = activeThreads.size
        fun getActiveThreadNames() = activeThreads.keys.toList()
        fun getCurrentQueueDepth() = queueDepth.get()
        fun getMaxQueueDepth() = maxQueueDepth.get()
    }

    /**
     * Execution time tracker for a single scanner
     */
    private class ExecutionTracker(val scannerId: String) {
        private val executionTimes = mutableListOf<ExecutionRecord>()
        private val errors = mutableListOf<ErrorRecord>()
        private val activeExecutions = ConcurrentHashMap<Long, Long>()

        private val totalExecutions = AtomicLong(0)
        private val totalSuccesses = AtomicLong(0)
        private val totalFailures = AtomicLong(0)
        private val totalItems = AtomicLong(0)
        private val executionIdGenerator = AtomicLong(0)

        @Volatile private var lastExecutionTime = 0L
        @Volatile private var lastErrorTime = 0L
        @Volatile private var lastErrorMessage: String? = null

        @Synchronized
        fun startExecution(): Long {
            val id = executionIdGenerator.incrementAndGet()
            activeExecutions[id] = System.currentTimeMillis()
            return id
        }

        @Synchronized
        fun endExecution(executionId: Long, success: Boolean, itemsProcessed: Int) {
            val startTime = activeExecutions.remove(executionId) ?: return
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            totalExecutions.incrementAndGet()
            if (success) {
                totalSuccesses.incrementAndGet()
            } else {
                totalFailures.incrementAndGet()
            }
            totalItems.addAndGet(itemsProcessed.toLong())
            lastExecutionTime = endTime

            // Add to rolling window
            executionTimes.add(ExecutionRecord(endTime, duration, success, itemsProcessed))

            // Trim old records
            val cutoff = System.currentTimeMillis() - METRICS_WINDOW_MS
            executionTimes.removeAll { it.timestamp < cutoff }
            if (executionTimes.size > MAX_EXECUTION_HISTORY) {
                executionTimes.removeAt(0)
            }
        }

        @Synchronized
        fun recordError(errorType: String, message: String) {
            val now = System.currentTimeMillis()
            lastErrorTime = now
            lastErrorMessage = "$errorType: $message"

            errors.add(ErrorRecord(now, errorType, message))

            // Trim old errors
            val cutoff = System.currentTimeMillis() - METRICS_WINDOW_MS
            errors.removeAll { it.timestamp < cutoff }
            if (errors.size > MAX_ERROR_HISTORY) {
                errors.removeAt(0)
            }
        }

        @Synchronized
        fun getAverageExecutionTime(): Double {
            if (executionTimes.isEmpty()) return 0.0
            return executionTimes.map { it.durationMs }.average()
        }

        @Synchronized
        fun getMaxExecutionTime(): Long {
            return executionTimes.maxOfOrNull { it.durationMs } ?: 0L
        }

        @Synchronized
        fun getMinExecutionTime(): Long {
            return executionTimes.minOfOrNull { it.durationMs } ?: 0L
        }

        @Synchronized
        fun getExecutionsPerMinute(): Double {
            val cutoff = System.currentTimeMillis() - 60_000
            val recentCount = executionTimes.count { it.timestamp > cutoff }
            return recentCount.toDouble()
        }

        fun getSuccessRate(): Double {
            val total = totalExecutions.get()
            if (total == 0L) return 1.0
            return totalSuccesses.get().toDouble() / total
        }

        fun getTotalExecutions() = totalExecutions.get()
        fun getTotalSuccesses() = totalSuccesses.get()
        fun getTotalFailures() = totalFailures.get()
        fun getTotalItemsProcessed() = totalItems.get()
        fun getLastExecutionTime() = lastExecutionTime
        fun getLastErrorTime() = lastErrorTime
        fun getLastErrorMessage() = lastErrorMessage

        @Synchronized
        fun getRecentErrors(): List<ErrorRecord> = errors.toList()
    }

    /**
     * IPC metrics tracker
     */
    private class IpcMetrics {
        private val messagesSent = mutableListOf<Long>()
        private val messagesReceived = mutableListOf<Pair<Long, Long>>() // timestamp, latency
        private val clientCount = AtomicInteger(0)

        @Synchronized
        fun recordMessageSent(messageType: Int) {
            val now = System.currentTimeMillis()
            messagesSent.add(now)
            trimOldRecords()
        }

        @Synchronized
        fun recordMessageReceived(messageType: Int, latencyMs: Long) {
            val now = System.currentTimeMillis()
            messagesReceived.add(now to latencyMs)
            trimOldRecords()
        }

        fun updateClientCount(count: Int) {
            clientCount.set(count)
        }

        @Synchronized
        private fun trimOldRecords() {
            val cutoff = System.currentTimeMillis() - METRICS_WINDOW_MS
            messagesSent.removeAll { it < cutoff }
            messagesReceived.removeAll { it.first < cutoff }
        }

        @Synchronized
        fun getMessagesSentPerMinute(): Double {
            val cutoff = System.currentTimeMillis() - 60_000
            return messagesSent.count { it > cutoff }.toDouble()
        }

        @Synchronized
        fun getMessagesReceivedPerMinute(): Double {
            val cutoff = System.currentTimeMillis() - 60_000
            return messagesReceived.count { it.first > cutoff }.toDouble()
        }

        @Synchronized
        fun getAverageLatency(): Double {
            if (messagesReceived.isEmpty()) return 0.0
            return messagesReceived.map { it.second }.average()
        }

        fun getClientCount() = clientCount.get()
    }

    // ========== Data Classes ==========

    data class JobInfo(
        val id: String,
        val name: String,
        val startTime: Long,
        val job: Job
    )

    data class ExecutionRecord(
        val timestamp: Long,
        val durationMs: Long,
        val success: Boolean,
        val itemsProcessed: Int
    )

    data class ErrorRecord(
        val timestamp: Long,
        val errorType: String,
        val message: String
    )

    data class MemoryInfo(
        val heapUsedMb: Long,
        val heapMaxMb: Long,
        val nativeHeapMb: Long
    )

    data class SystemThreadingState(
        val timestamp: Long = 0L,
        val totalActiveThreads: Int = 0,
        val totalActiveJobs: Int = 0,
        val processThreadCount: Int = 0,
        val averageSuccessRate: Double = 1.0,
        val totalExecutionsPerMinute: Double = 0.0,
        val heapUsedMb: Long = 0,
        val heapMaxMb: Long = 0,
        val nativeHeapMb: Long = 0,
        val ipcMessagesSentPerMinute: Double = 0.0,
        val ipcMessagesReceivedPerMinute: Double = 0.0,
        val ipcAverageLatencyMs: Double = 0.0,
        val ipcConnectedClients: Int = 0,
        val stalledScanners: List<String> = emptyList(),
        val healthScore: Float = 100f
    )

    data class ScannerThreadState(
        val scannerId: String,
        val activeThreadCount: Int = 0,
        val activeJobCount: Int = 0,
        val totalJobsStarted: Int = 0,
        val currentQueueDepth: Int = 0,
        val maxQueueDepth: Int = 0,
        val avgExecutionTimeMs: Double = 0.0,
        val maxExecutionTimeMs: Long = 0,
        val minExecutionTimeMs: Long = 0,
        val executionsPerMinute: Double = 0.0,
        val successRate: Double = 1.0,
        val totalExecutions: Long = 0,
        val totalSuccesses: Long = 0,
        val totalFailures: Long = 0,
        val totalItemsProcessed: Long = 0,
        val lastExecutionTime: Long = 0,
        val lastErrorTime: Long = 0,
        val lastErrorMessage: String? = null,
        val recentErrors: List<ErrorRecord> = emptyList(),
        val isStalled: Boolean = false,
        val stalledDurationMs: Long = 0,
        val threadNames: List<String> = emptyList()
    )

    data class ThreadingAlert(
        val severity: AlertSeverity,
        val scannerId: String,
        val title: String,
        val message: String,
        val timestamp: Long
    )

    enum class AlertSeverity {
        INFO,
        WARNING,
        ERROR
    }
}

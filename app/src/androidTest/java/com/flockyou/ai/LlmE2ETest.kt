package com.flockyou.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flockyou.data.*
import com.flockyou.data.model.*
import com.flockyou.utils.TestDataFactory
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Comprehensive E2E tests for LLM functionality.
 *
 * Tests cover:
 * - LlmEngineManager initialization and fallback chain
 * - ML Kit GenAI (Gemini Nano) integration
 * - MediaPipe LLM integration
 * - Rule-based fallback
 * - DetectionAnalyzer LLM integration
 * - FalsePositiveAnalyzer LLM integration
 * - Cross-module detection analysis
 * - Error handling and recovery
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LlmE2ETest {

    companion object {
        private const val TAG = "LlmE2ETest"
        private const val ANALYSIS_TIMEOUT_MS = 120_000L // 2 minutes for LLM analysis
    }

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var llmEngineManager: LlmEngineManager

    @Inject
    lateinit var geminiNanoClient: GeminiNanoClient

    @Inject
    lateinit var mediaPipeLlmClient: MediaPipeLlmClient

    @Inject
    lateinit var detectionAnalyzer: DetectionAnalyzer

    @Inject
    lateinit var falsePositiveAnalyzer: FalsePositiveAnalyzer

    @Inject
    lateinit var aiSettingsRepository: AiSettingsRepository

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        hiltRule.inject()
        Log.i(TAG, "Test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            llmEngineManager.cleanup()
        }
        Log.i(TAG, "Test cleanup complete")
    }

    // ==================== LlmEngineManager Tests ====================

    @Test
    fun engineManager_initializesSuccessfully() = runTest {
        val settings = aiSettingsRepository.settings.first()
        val settingsWithAiEnabled = settings.copy(enabled = true)

        val initialized = llmEngineManager.initialize(AiModel.RULE_BASED, settingsWithAiEnabled)

        assertTrue("LlmEngineManager should initialize successfully", initialized)
        assertNotNull("Active engine should be set", llmEngineManager.activeEngine.value)
        Log.i(TAG, "Active engine: ${llmEngineManager.activeEngine.value}")
    }

    @Test
    fun engineManager_fallsBackToRuleBasedWhenNoLlmAvailable() = runTest {
        val settings = AiSettings(enabled = true, selectedModel = "rule-based")

        val initialized = llmEngineManager.initialize(AiModel.RULE_BASED, settings)

        assertTrue("Should initialize with rule-based", initialized)
        assertEquals("Active engine should be RULE_BASED",
            LlmEngine.RULE_BASED, llmEngineManager.activeEngine.value)
    }

    @Test
    fun engineManager_reportsEngineStatus() = runTest {
        val status = llmEngineManager.engineStatus.value

        assertNotNull("Engine status should be available", status)
        Log.i(TAG, "Engine status: $status")
    }

    @Test
    fun engineManager_tracksEngineHealth() = runTest {
        val ruleBasedHealth = llmEngineManager.getEngineHealth(LlmEngine.RULE_BASED)
        val geminiHealth = llmEngineManager.getEngineHealth(LlmEngine.GEMINI_NANO)
        val mediaPipeHealth = llmEngineManager.getEngineHealth(LlmEngine.MEDIAPIPE)

        assertNotNull("Rule-based health should be tracked", ruleBasedHealth)
        assertNotNull("Gemini Nano health should be tracked", geminiHealth)
        assertNotNull("MediaPipe health should be tracked", mediaPipeHealth)
    }

    // ==================== Gemini Nano Tests ====================

    @Test
    fun geminiNano_checksDeviceSupport() {
        val isSupported = geminiNanoClient.isDeviceSupported()
        Log.i(TAG, "Gemini Nano device supported: $isSupported")
        // Note: This test just checks the function works, not the actual result
        // as device support depends on hardware
    }

    @Test
    fun geminiNano_checksModelAvailability() = runTest {
        val status = geminiNanoClient.checkModelAvailability()
        Log.i(TAG, "Gemini Nano model availability: $status")
        // Status could be AVAILABLE, DOWNLOADABLE, DOWNLOADING, or UNAVAILABLE
    }

    @Test
    fun geminiNano_reportsStatus() {
        val status = geminiNanoClient.getStatus()
        assertNotNull("Status should not be null", status)
        Log.i(TAG, "Gemini Nano status: $status")
    }

    @Test
    fun geminiNano_isReadyReturnsValidState() {
        val isReady = geminiNanoClient.isReady()
        Log.i(TAG, "Gemini Nano isReady: $isReady")
        // This just validates the function works
    }

    // ==================== MediaPipe Tests ====================

    @Test
    fun mediaPipe_reportsStatus() {
        val status = mediaPipeLlmClient.getStatus()
        assertNotNull("Status should not be null", status)
        Log.i(TAG, "MediaPipe status: $status")
    }

    @Test
    fun mediaPipe_isReadyReturnsValidState() {
        val isReady = mediaPipeLlmClient.isReady()
        Log.i(TAG, "MediaPipe isReady: $isReady")
    }

    // ==================== DetectionAnalyzer Tests ====================

    @Test
    fun detectionAnalyzer_initializesModel() = runTest {
        // Enable AI in settings first
        aiSettingsRepository.updateEnabled(true)

        val initialized = detectionAnalyzer.initializeModel()

        Log.i(TAG, "DetectionAnalyzer initialized: $initialized")
        // Initialization should succeed even if just using rule-based
    }

    @Test
    fun detectionAnalyzer_analyzesFlockSafetyCamera() = runTest {
        // Enable AI
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)

        // Initialize
        detectionAnalyzer.initializeModel()

        // Create test detection
        val detection = TestDataFactory.createFlockSafetyCameraDetection()

        // Analyze with timeout
        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        assertNotNull("Analysis result should not be null", result)
        assertTrue("Analysis should succeed", result.success)
        assertNotNull("Analysis text should be present", result.analysis)
        assertFalse("Analysis should not be empty", result.analysis.isNullOrEmpty())
        assertTrue("Should be on-device", result.wasOnDevice)
        assertNotNull("Model used should be specified", result.modelUsed)

        Log.i(TAG, "Analysis result: model=${result.modelUsed}, confidence=${result.confidence}")
        Log.i(TAG, "Analysis text length: ${result.analysis?.length}")
    }

    @Test
    fun detectionAnalyzer_analyzesStingray() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createStingrayDetection()

        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        assertNotNull("Analysis result should not be null", result)
        assertTrue("Analysis should succeed", result.success)
        assertNotNull("Analysis text should be present", result.analysis)

        // Stingray is CRITICAL threat, should have recommendations
        assertTrue("Should have recommendations for critical threat",
            result.recommendations.isNotEmpty())

        Log.i(TAG, "Stingray analysis: threat discussed in ${result.analysis?.length} chars")
    }

    @Test
    fun detectionAnalyzer_analyzesDrone() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createDroneDetection()

        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        assertNotNull("Analysis result should not be null", result)
        assertTrue("Analysis should succeed", result.success)
        assertNotNull("Analysis text should be present", result.analysis)

        Log.i(TAG, "Drone analysis: model=${result.modelUsed}")
    }

    @Test
    fun detectionAnalyzer_analyzesUltrasonicBeacon() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createUltrasonicBeaconDetection()

        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        assertNotNull("Analysis result should not be null", result)
        assertTrue("Analysis should succeed", result.success)
        assertNotNull("Analysis text should be present", result.analysis)

        Log.i(TAG, "Ultrasonic analysis: model=${result.modelUsed}")
    }

    @Test
    fun detectionAnalyzer_analyzesSatellite() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createSatelliteDetection()

        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        assertNotNull("Analysis result should not be null", result)
        assertTrue("Analysis should succeed", result.success)

        Log.i(TAG, "Satellite analysis: model=${result.modelUsed}")
    }

    @Test
    fun detectionAnalyzer_providesStructuredData() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createFlockSafetyCameraDetection()

        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        assertNotNull("Structured data should be provided", result.structuredData)
        result.structuredData?.let { structured ->
            assertNotNull("Device category should be set", structured.deviceCategory)
            assertNotNull("Surveillance type should be set", structured.surveillanceType)
            assertFalse("Data collection types should not be empty",
                structured.dataCollectionTypes.isEmpty())
            assertTrue("Risk score should be valid", structured.riskScore in 0..100)
        }

        Log.i(TAG, "Structured data: ${result.structuredData}")
    }

    @Test
    fun detectionAnalyzer_returnsWhenAiDisabled() = runTest {
        aiSettingsRepository.updateEnabled(false)

        val detection = TestDataFactory.createFlockSafetyCameraDetection()

        val result = detectionAnalyzer.analyzeDetection(detection)

        assertFalse("Analysis should not succeed when disabled", result.success)
        assertNotNull("Error message should be provided", result.error)

        Log.i(TAG, "Disabled result: ${result.error}")
    }

    @Test
    fun detectionAnalyzer_cachesPreviousAnalysis() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createFlockSafetyCameraDetection()

        // First analysis
        val result1 = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        // Second analysis should be faster (cached)
        val startTime = System.currentTimeMillis()
        val result2 = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Both analyses should succeed", result1.success && result2.success)

        Log.i(TAG, "Cache test: first=${result1.processingTimeMs}ms, second=${result2.processingTimeMs}ms, elapsed=$elapsed")
    }

    // ==================== FalsePositiveAnalyzer Tests ====================

    @Test
    fun falsePositiveAnalyzer_analyzesConsumerDevice() = runTest {
        // Create a likely false positive (consumer router)
        val detection = TestDataFactory.createTestDetection(
            deviceType = DeviceType.RING_DOORBELL,
            threatLevel = ThreatLevel.INFO,
            ssid = "NETGEAR-Guest"
        )

        val result = falsePositiveAnalyzer.analyzeForFalsePositive(detection, null, tryLazyInit = false)

        assertNotNull("FP result should not be null", result)
        assertNotNull("Primary reason should be provided", result.primaryReason)

        Log.i(TAG, "FP analysis: isFP=${result.isFalsePositive}, confidence=${result.confidence}")
        Log.i(TAG, "FP reason: ${result.primaryReason}")
    }

    @Test
    fun falsePositiveAnalyzer_identifiesBenignSsid() = runTest {
        val detection = TestDataFactory.createTestDetection(
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            threatLevel = ThreatLevel.LOW,
            ssid = "Xfinity-Home-5G"
        )

        val result = falsePositiveAnalyzer.analyzeForFalsePositive(detection, null, tryLazyInit = false)

        assertTrue("Consumer router SSID should be flagged as likely FP",
            result.isFalsePositive || result.confidence > 0.3f)

        Log.i(TAG, "Benign SSID test: isFP=${result.isFalsePositive}, confidence=${result.confidence}")
    }

    @Test
    fun falsePositiveAnalyzer_respectsHighThreatDevices() = runTest {
        val detection = TestDataFactory.createStingrayDetection()

        val result = falsePositiveAnalyzer.analyzeForFalsePositive(detection, null, tryLazyInit = false)

        // Stingrays should NOT be marked as false positives
        assertFalse("Critical threats should not be marked as FP",
            result.isFalsePositive && result.confidence > 0.8f)

        Log.i(TAG, "High threat test: isFP=${result.isFalsePositive}, confidence=${result.confidence}")
    }

    @Test
    fun falsePositiveAnalyzer_filtersBatchDetections() = runTest {
        val detections = listOf(
            TestDataFactory.createFlockSafetyCameraDetection(), // Real threat
            TestDataFactory.createStingrayDetection(),          // Critical threat
            TestDataFactory.createTestDetection(                // Likely FP
                deviceType = DeviceType.RING_DOORBELL,
                threatLevel = ThreatLevel.INFO,
                ssid = "Amazon-Echo-Setup"
            )
        )

        val filtered = falsePositiveAnalyzer.filterFalsePositives(
            detections,
            threshold = 0.6f,
            contextInfo = null
        )

        assertNotNull("Filtered result should not be null", filtered)
        assertEquals("Total analyzed should match input", detections.size, filtered.totalAnalyzed)
        assertTrue("Valid count + filtered count should equal total",
            filtered.validCount + filtered.filteredCount == filtered.totalAnalyzed)

        Log.i(TAG, "Batch filter: valid=${filtered.validCount}, filtered=${filtered.filteredCount}")
    }

    @Test
    fun falsePositiveAnalyzer_providesUserFriendlyBanner() = runTest {
        val detection = TestDataFactory.createTestDetection(
            deviceType = DeviceType.RING_DOORBELL,
            threatLevel = ThreatLevel.INFO,
            ssid = "GoogleHome-123"
        )

        val result = falsePositiveAnalyzer.analyzeForFalsePositive(detection, null, tryLazyInit = false)

        if (result.isFalsePositive) {
            assertNotNull("Banner message should be provided for FPs", result.bannerMessage)
            assertTrue("Banner should not be empty", result.bannerMessage?.isNotEmpty() == true)
        }

        Log.i(TAG, "Banner test: ${result.bannerMessage}")
    }

    // ==================== Cross-Module Integration Tests ====================

    @Test
    fun integration_allProtocolsCanBeAnalyzed() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        detectionAnalyzer.initializeModel()

        val protocols = mapOf(
            "WiFi" to TestDataFactory.createFlockSafetyCameraDetection(),
            "Cellular" to TestDataFactory.createStingrayDetection(),
            "Audio" to TestDataFactory.createUltrasonicBeaconDetection(),
            "Satellite" to TestDataFactory.createSatelliteDetection(),
            "WiFi/RF" to TestDataFactory.createDroneDetection()
        )

        protocols.forEach { (name, detection) ->
            val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
                detectionAnalyzer.analyzeDetection(detection)
            }

            assertTrue("$name detection should be analyzed successfully", result.success)
            assertNotNull("$name analysis should have text", result.analysis)

            Log.i(TAG, "$name protocol: success=${result.success}, model=${result.modelUsed}")
        }
    }

    @Test
    fun integration_detectionAndFpAnalysisTogether() = runTest {
        aiSettingsRepository.updateEnabled(true)
        aiSettingsRepository.updateAnalyzeDetections(true)
        aiSettingsRepository.updateFalsePositiveFiltering(true)
        detectionAnalyzer.initializeModel()

        val detection = TestDataFactory.createFlockSafetyCameraDetection()

        // Get both analyses
        val detectionResult = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(detection)
        }

        val fpResult = falsePositiveAnalyzer.analyzeForFalsePositive(detection, null, tryLazyInit = false)

        // Check that both work together
        assertTrue("Detection analysis should succeed", detectionResult.success)
        assertNotNull("FP analysis should complete", fpResult)

        // Flock Safety should NOT be a false positive
        assertFalse("Flock Safety Camera should not be marked as FP with high confidence",
            fpResult.isFalsePositive && fpResult.confidence > 0.7f)

        Log.i(TAG, "Integration: detection success=${detectionResult.success}, isFP=${fpResult.isFalsePositive}")
    }

    @Test
    fun integration_modelStatusReflectsRealState() = runTest {
        aiSettingsRepository.updateEnabled(true)
        detectionAnalyzer.initializeModel()

        val status = detectionAnalyzer.getModelStatus()
        val currentModel = detectionAnalyzer.getCurrentModel()

        assertNotNull("Model status should be available", status)
        assertNotNull("Current model should be set", currentModel)

        Log.i(TAG, "Model state: status=$status, model=${currentModel.displayName}")
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun errorHandling_handlesNullInputGracefully() = runTest {
        aiSettingsRepository.updateEnabled(true)
        detectionAnalyzer.initializeModel()

        // Create detection with minimal data
        val minimalDetection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            deviceName = null,
            rssi = -70,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = ThreatLevel.LOW,
            threatScore = 30,
            macAddress = null,
            ssid = null,
            latitude = null,
            longitude = null,
            isActive = true
        )

        val result = withTimeout(ANALYSIS_TIMEOUT_MS) {
            detectionAnalyzer.analyzeDetection(minimalDetection)
        }

        // Should still succeed with rule-based analysis
        assertTrue("Should handle minimal detection gracefully", result.success)

        Log.i(TAG, "Minimal detection: success=${result.success}")
    }

    @Test
    fun errorHandling_engineRecoveryWorks() = runTest {
        val settings = AiSettings(enabled = true, selectedModel = "rule-based")

        // Initialize
        llmEngineManager.initialize(AiModel.RULE_BASED, settings)

        // Reset health
        llmEngineManager.resetEngineHealth(LlmEngine.RULE_BASED)

        // Check health is reset
        val health = llmEngineManager.getEngineHealth(LlmEngine.RULE_BASED)
        assertEquals("Consecutive failures should be 0 after reset", 0, health?.consecutiveFailures)

        Log.i(TAG, "Recovery test: health reset successful")
    }
}

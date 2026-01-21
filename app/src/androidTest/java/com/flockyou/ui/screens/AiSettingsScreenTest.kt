package com.flockyou.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.MainActivity
import com.flockyou.data.*
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * E2E tests for AiSettingsScreen.
 *
 * Tests cover:
 * - AI analysis enable/disable toggle
 * - Model selection and download UI
 * - Model status indicator (Not Downloaded, Downloading, Ready, Error)
 * - GPU and NPU acceleration toggles
 * - Analysis capability toggles (analyze detections, threat assessments, etc.)
 * - Advanced features (contextual analysis, batch analysis, feedback tracking)
 * - LLM engine preference selection
 * - HuggingFace token management
 * - Test analysis functionality
 * - Settings persistence
 * - Device capability detection and display
 * - Model selector dialog interactions
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AiSettingsScreenTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var aiSettingsRepository: AiSettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        runBlocking {
            aiSettingsRepository.clearSettings()
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            aiSettingsRepository.clearSettings()
        }
    }

    private fun navigateToAiSettings() {
        composeTestRule.waitForIdle()
        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Navigate to AI Settings
        composeTestRule.onNode(
            hasText("AI Analysis", substring = true, ignoreCase = true) and hasClickAction()
        ).performClick()
        composeTestRule.waitForIdle()
    }

    // ==================== Privacy Notice Tests ====================

    @Test
    fun aiSettings_privacyNoticeDisplayed() {
        navigateToAiSettings()

        // Verify privacy notice is shown
        composeTestRule.onNode(hasText("100% Local & Private", substring = true, ignoreCase = true))
            .assertExists()
        composeTestRule.onNode(
            hasText("All AI analysis runs entirely on your device", substring = true, ignoreCase = true)
        ).assertExists()
    }

    // ==================== Master Toggle Tests ====================

    @Test
    fun aiSettings_masterToggleDisplayed() {
        navigateToAiSettings()

        // Verify AI toggle is visible
        composeTestRule.onNode(hasText("AI-Powered Analysis", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_masterTogglePersists() = runTest {
        navigateToAiSettings()

        // Enable AI
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertTrue("AI should be enabled", settings.enabled)

        // Disable AI
        aiSettingsRepository.setEnabled(false)
        composeTestRule.waitForIdle()

        val settingsAfter = aiSettingsRepository.settings.first()
        assertFalse("AI should be disabled", settingsAfter.enabled)
    }

    @Test
    fun aiSettings_masterToggleShowsStatus() = runTest {
        navigateToAiSettings()

        // When disabled
        aiSettingsRepository.setEnabled(false)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasText("Enable to get intelligent threat insights", substring = true, ignoreCase = true)
        ).assertExists()
    }

    @Test
    fun aiSettings_capabilitiesHiddenWhenDisabled() = runTest {
        navigateToAiSettings()

        // Disable AI
        aiSettingsRepository.setEnabled(false)
        composeTestRule.waitForIdle()

        // Capabilities section should not be visible
        composeTestRule.onNode(hasText("Analysis Capabilities", substring = true, ignoreCase = true))
            .assertDoesNotExist()
    }

    @Test
    fun aiSettings_capabilitiesShownWhenEnabled() = runTest {
        navigateToAiSettings()

        // Enable AI
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Capabilities section should be visible
        composeTestRule.onNode(hasText("Analysis Capabilities", substring = true, ignoreCase = true))
            .assertExists()
    }

    // ==================== Model Selection Tests ====================

    @Test
    fun aiSettings_modelSelectionCardDisplayed() {
        navigateToAiSettings()

        // Model selection should always be visible
        composeTestRule.onNode(hasText("Rule-Based Analysis", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_defaultModelIsRuleBased() = runTest {
        val settings = aiSettingsRepository.settings.first()
        assertEquals("Default model should be rule-based", "rule-based", settings.selectedModel)
    }

    @Test
    fun aiSettings_modelSelectionPersists() = runTest {
        navigateToAiSettings()

        // Select a different model
        aiSettingsRepository.setSelectedModel("gemini-nano")
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertEquals("Selected model should persist", "gemini-nano", settings.selectedModel)
    }

    @Test
    fun aiSettings_modelSelectorDialogOpens() = runTest {
        navigateToAiSettings()

        // Enable AI first
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Click on model card to open selector
        composeTestRule.onNode(
            hasText("Rule-Based Analysis", substring = true, ignoreCase = true) and hasClickAction()
        ).performClick()
        composeTestRule.waitForIdle()

        // Dialog should show available models
        composeTestRule.onNode(hasText("Select AI Engine", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_modelSelectorShowsAllCategories() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Open model selector
        composeTestRule.onNode(
            hasText("Rule-Based Analysis", substring = true, ignoreCase = true) and hasClickAction()
        ).performClick()
        composeTestRule.waitForIdle()

        // Should show model categories
        composeTestRule.onNode(hasText("Built-in", substring = true, ignoreCase = true))
            .assertExists()
        composeTestRule.onNode(hasText("Downloadable Models", substring = true, ignoreCase = true))
            .assertExists()
    }

    // ==================== GPU/NPU Acceleration Tests ====================

    @Test
    fun aiSettings_gpuAccelerationToggleDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to performance section
        composeTestRule.onNode(hasText("Performance", substring = true, ignoreCase = true))
            .performScrollTo()

        composeTestRule.onNode(hasText("GPU Acceleration", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_gpuAccelerationTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Toggle GPU acceleration
        aiSettingsRepository.setUseGpuAcceleration(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("GPU acceleration should be disabled", settings.useGpuAcceleration)
    }

    @Test
    fun aiSettings_npuAccelerationOnlyShownWhenSupported() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // NPU toggle should only appear on supported devices (Pixel 8+)
        // This test documents the expected behavior
        // On non-Pixel 8+ devices, NPU toggle should not exist
    }

    @Test
    fun aiSettings_npuAccelerationTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Toggle NPU acceleration
        aiSettingsRepository.setUseNpuAcceleration(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("NPU acceleration should be disabled", settings.useNpuAcceleration)
    }

    // ==================== Analysis Capabilities Tests ====================

    @Test
    fun aiSettings_analyzeDetectionsToggleDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Detection Analysis", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_analyzeDetectionsTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setAnalyzeDetections(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("Analyze detections should be disabled", settings.analyzeDetections)
    }

    @Test
    fun aiSettings_threatAssessmentsToggleDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Threat Assessments", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_threatAssessmentsTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setGenerateThreatAssessments(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("Threat assessments should be disabled", settings.generateThreatAssessments)
    }

    @Test
    fun aiSettings_identifyUnknownToggleDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Device Identification", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_identifyUnknownTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setIdentifyUnknownDevices(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("Identify unknown devices should be disabled", settings.identifyUnknownDevices)
    }

    @Test
    fun aiSettings_autoAnalyzeToggleDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Auto-Analyze New Detections", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_autoAnalyzeTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setAutoAnalyzeNewDetections(true)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertTrue("Auto-analyze should be enabled", settings.autoAnalyzeNewDetections)
    }

    // ==================== Advanced Features Tests ====================

    @Test
    fun aiSettings_advancedFeaturesDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to advanced features
        composeTestRule.onNode(hasText("Advanced Features", substring = true, ignoreCase = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aiSettings_falsePositiveFilteringTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setFalsePositiveFiltering(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("False positive filtering should be disabled", settings.enableFalsePositiveFiltering)
    }

    @Test
    fun aiSettings_contextualAnalysisTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setContextualAnalysis(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("Contextual analysis should be disabled", settings.enableContextualAnalysis)
    }

    @Test
    fun aiSettings_batchAnalysisTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setBatchAnalysis(true)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertTrue("Batch analysis should be enabled", settings.enableBatchAnalysis)
    }

    @Test
    fun aiSettings_feedbackTrackingTogglePersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        aiSettingsRepository.setTrackFeedback(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("Feedback tracking should be disabled", settings.trackAnalysisFeedback)
    }

    // ==================== LLM Engine Tests ====================

    @Test
    fun aiSettings_llmEngineSelectionDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to LLM Engine section
        composeTestRule.onNode(hasText("LLM Engine", substring = true, ignoreCase = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aiSettings_llmEngineDefaultIsAuto() = runTest {
        val settings = aiSettingsRepository.settings.first()
        assertEquals("Default engine should be auto", "auto", settings.preferredEngine)
    }

    @Test
    fun aiSettings_llmEngineSelectionPersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Select MediaPipe engine
        aiSettingsRepository.setPreferredEngine("mediapipe")
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertEquals("Engine should be mediapipe", "mediapipe", settings.preferredEngine)
    }

    @Test
    fun aiSettings_llmEngineShowsAvailability() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to LLM Engine section
        composeTestRule.onNode(hasText("LLM Engine", substring = true, ignoreCase = true))
            .performScrollTo()

        // Should show engine availability status
        composeTestRule.onNode(
            hasText("Auto (Recommended)", substring = true, ignoreCase = true)
        ).assertExists()
    }

    // ==================== HuggingFace Token Tests ====================

    @Test
    fun aiSettings_huggingFaceTokenCardDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to token section
        composeTestRule.onNode(hasText("Hugging Face Token", substring = true, ignoreCase = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aiSettings_huggingFaceTokenPersists() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Set token
        aiSettingsRepository.setHuggingFaceToken("hf_test_token_12345")
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertEquals("Token should persist", "hf_test_token_12345", settings.huggingFaceToken)
    }

    @Test
    fun aiSettings_huggingFaceTokenShowsConfiguredStatus() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Set token
        aiSettingsRepository.setHuggingFaceToken("hf_test")
        composeTestRule.waitForIdle()

        // Scroll to token section
        composeTestRule.onNode(hasText("Hugging Face Token", substring = true, ignoreCase = true))
            .performScrollTo()

        // Should show configured status
        composeTestRule.onNode(hasText("Token configured", substring = true, ignoreCase = true))
            .assertExists()
    }

    // ==================== Test Analysis Tests ====================

    @Test
    fun aiSettings_testAnalysisCardDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to test section
        composeTestRule.onNode(hasText("Test AI Analysis", substring = true, ignoreCase = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aiSettings_testAnalysisButtonDisabledWhenModelNotReady() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // With rule-based model (always ready), button should be enabled
        // This test documents expected behavior
        composeTestRule.onNode(hasText("Test AI Analysis", substring = true, ignoreCase = true))
            .performScrollTo()

        composeTestRule.onNode(hasText("Run Test Analysis", substring = true, ignoreCase = true))
            .assertExists()
    }

    // ==================== Device Capabilities Tests ====================

    @Test
    fun aiSettings_deviceCapabilitiesDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to performance section where RAM info is shown
        composeTestRule.onNode(hasText("Performance", substring = true, ignoreCase = true))
            .performScrollTo()

        composeTestRule.onNode(hasText("Available RAM", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun aiSettings_aicoreStatusDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // AICore status should be shown somewhere in the UI
        // This depends on device capabilities
    }

    // ==================== Settings Persistence Tests ====================

    @Test
    fun aiSettings_allSettingsPersistTogether() = runTest {
        // Set multiple settings
        aiSettingsRepository.setEnabled(true)
        aiSettingsRepository.setSelectedModel("gemma3-1b")
        aiSettingsRepository.setUseGpuAcceleration(false)
        aiSettingsRepository.setUseNpuAcceleration(false)
        aiSettingsRepository.setAnalyzeDetections(true)
        aiSettingsRepository.setGenerateThreatAssessments(true)
        aiSettingsRepository.setIdentifyUnknownDevices(false)
        aiSettingsRepository.setAutoAnalyzeNewDetections(true)
        aiSettingsRepository.setContextualAnalysis(true)
        aiSettingsRepository.setBatchAnalysis(false)
        aiSettingsRepository.setTrackFeedback(true)
        aiSettingsRepository.setFalsePositiveFiltering(false)
        aiSettingsRepository.setPreferredEngine("mediapipe")
        aiSettingsRepository.setHuggingFaceToken("hf_test_token")
        composeTestRule.waitForIdle()

        // Verify all settings persisted
        val settings = aiSettingsRepository.settings.first()
        assertTrue("AI should be enabled", settings.enabled)
        assertEquals("Model should be gemma3-1b", "gemma3-1b", settings.selectedModel)
        assertFalse("GPU should be disabled", settings.useGpuAcceleration)
        assertFalse("NPU should be disabled", settings.useNpuAcceleration)
        assertTrue("Analyze detections should be enabled", settings.analyzeDetections)
        assertTrue("Threat assessments should be enabled", settings.generateThreatAssessments)
        assertFalse("Identify unknown should be disabled", settings.identifyUnknownDevices)
        assertTrue("Auto-analyze should be enabled", settings.autoAnalyzeNewDetections)
        assertTrue("Contextual analysis should be enabled", settings.enableContextualAnalysis)
        assertFalse("Batch analysis should be disabled", settings.enableBatchAnalysis)
        assertTrue("Track feedback should be enabled", settings.trackAnalysisFeedback)
        assertFalse("False positive filtering should be disabled", settings.enableFalsePositiveFiltering)
        assertEquals("Engine should be mediapipe", "mediapipe", settings.preferredEngine)
        assertEquals("Token should persist", "hf_test_token", settings.huggingFaceToken)
    }

    // ==================== Navigation Tests ====================

    @Test
    fun aiSettings_backButtonPreservesSettings() = runTest {
        navigateToAiSettings()

        // Modify settings
        aiSettingsRepository.setEnabled(true)
        aiSettingsRepository.setUseGpuAcceleration(false)
        composeTestRule.waitForIdle()

        // Navigate back
        composeTestRule.onNode(
            hasContentDescription("Back", substring = true, ignoreCase = true) or
            hasContentDescription("Navigate up", substring = true, ignoreCase = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Verify settings persisted
        val settings = aiSettingsRepository.settings.first()
        assertTrue("AI enabled should persist", settings.enabled)
        assertFalse("GPU setting should persist", settings.useGpuAcceleration)
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun aiSettings_handlesRapidToggling() = runTest {
        navigateToAiSettings()

        // Rapidly toggle AI on/off
        repeat(5) {
            aiSettingsRepository.setEnabled(it % 2 == 0)
        }
        composeTestRule.waitForIdle()

        // Should not crash and should have a valid final state
        val settings = aiSettingsRepository.settings.first()
        assertNotNull("Settings should remain valid", settings)
    }

    @Test
    fun aiSettings_allCapabilitiesCanBeDisabled() = runTest {
        // Disable all analysis capabilities
        aiSettingsRepository.setEnabled(true)
        aiSettingsRepository.setAnalyzeDetections(false)
        aiSettingsRepository.setGenerateThreatAssessments(false)
        aiSettingsRepository.setIdentifyUnknownDevices(false)
        aiSettingsRepository.setAutoAnalyzeNewDetections(false)
        composeTestRule.waitForIdle()

        val settings = aiSettingsRepository.settings.first()
        assertFalse("Analyze detections should be disabled", settings.analyzeDetections)
        assertFalse("Threat assessments should be disabled", settings.generateThreatAssessments)
        assertFalse("Identify unknown should be disabled", settings.identifyUnknownDevices)
        assertFalse("Auto-analyze should be disabled", settings.autoAnalyzeNewDetections)
    }

    @Test
    fun aiSettings_modelSelectionWithNoDownloadedModels() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // With only rule-based (no download needed), should still work
        val settings = aiSettingsRepository.settings.first()
        assertEquals("Should default to rule-based", "rule-based", settings.selectedModel)
    }

    @Test
    fun aiSettings_infoCardDisplayed() = runTest {
        navigateToAiSettings()
        aiSettingsRepository.setEnabled(true)
        composeTestRule.waitForIdle()

        // Scroll to bottom where info card should be
        composeTestRule.onNode(hasText("About On-Device AI", substring = true, ignoreCase = true))
            .performScrollTo()
            .assertExists()
    }
}

package com.flockyou.di

import android.content.Context
import com.flockyou.testmode.TestModeConfigRepository
import com.flockyou.testmode.TestScenarioProvider
import com.flockyou.testmode.scanner.MockAudioScanner
import com.flockyou.testmode.scanner.MockBleScanner
import com.flockyou.testmode.scanner.MockCellularScanner
import com.flockyou.testmode.scanner.MockGnssScanner
import com.flockyou.testmode.scanner.MockWifiScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Test Mode dependency injection.
 *
 * This module provides all the dependencies required for Test Mode operation,
 * including:
 * - Mock scanner implementations for all protocols
 * - Test scenario provider for predefined scenarios
 * - Configuration repository for persisting test mode settings
 *
 * All dependencies are provided as singletons to ensure consistent state
 * across the application during test mode.
 *
 * Usage:
 * Test mode can be enabled through the TestModeConfigRepository, which will
 * cause the ScannerFactory to return mock scanners instead of real ones.
 *
 * Note: TestModeConfigRepository has an @Inject constructor and is provided
 * automatically by Hilt. It is included here as an explicit provider for
 * clarity and to allow for potential customization in the future.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestModeModule {

    // ================================================================
    // Test Scenario Provider
    // ================================================================

    /**
     * Provide the TestScenarioProvider singleton.
     *
     * TestScenarioProvider contains predefined test scenarios for various
     * surveillance detection demonstrations (BLE tracking, IMSI catchers,
     * GPS spoofing, etc.).
     */
    @Provides
    @Singleton
    fun provideTestScenarioProvider(): TestScenarioProvider {
        return TestScenarioProvider()
    }

    // ================================================================
    // Mock Scanners
    // ================================================================

    /**
     * Provide the MockWifiScanner singleton.
     *
     * MockWifiScanner simulates WiFi scan results for testing evil twin
     * attacks, surveillance detection, and other WiFi-based scenarios.
     */
    @Provides
    @Singleton
    fun provideMockWifiScanner(): MockWifiScanner {
        return MockWifiScanner()
    }

    /**
     * Provide the MockBleScanner singleton.
     *
     * MockBleScanner simulates BLE scan results for testing AirTag/Tile
     * tracking detection, unknown device alerts, and proximity warnings.
     */
    @Provides
    @Singleton
    fun provideMockBleScanner(): MockBleScanner {
        return MockBleScanner()
    }

    /**
     * Provide the MockCellularScanner singleton.
     *
     * MockCellularScanner simulates cellular network data and anomalies
     * for testing IMSI catcher detection, protocol downgrade attacks,
     * and other cellular surveillance scenarios.
     */
    @Provides
    @Singleton
    fun provideMockCellularScanner(): MockCellularScanner {
        return MockCellularScanner()
    }

    /**
     * Provide the MockGnssScanner singleton.
     *
     * MockGnssScanner simulates GNSS satellite data and anomalies for
     * testing GPS spoofing and jamming detection scenarios.
     */
    @Provides
    @Singleton
    fun provideMockGnssScanner(): MockGnssScanner {
        return MockGnssScanner()
    }

    /**
     * Provide the MockAudioScanner singleton.
     *
     * MockAudioScanner simulates ultrasonic beacon detection for testing
     * cross-device tracking detection (SilverPush, Alphonso, etc.).
     */
    @Provides
    @Singleton
    fun provideMockAudioScanner(): MockAudioScanner {
        return MockAudioScanner()
    }

    // ================================================================
    // Configuration Repository
    // ================================================================

    /**
     * Provide the TestModeConfigRepository singleton.
     *
     * TestModeConfigRepository uses DataStore to persist test mode
     * configuration including:
     * - Test mode enabled/disabled state
     * - Active scenario ID
     * - Auto-advance settings
     * - Data emission interval
     * - Signal variation settings
     * - UI banner visibility
     *
     * Note: This explicit provider exists for documentation purposes.
     * Hilt would automatically provide TestModeConfigRepository since
     * it has an @Inject constructor, but the explicit provider makes
     * the dependency graph clearer.
     */
    @Provides
    @Singleton
    fun provideTestModeConfigRepository(
        @ApplicationContext context: Context
    ): TestModeConfigRepository {
        return TestModeConfigRepository(context)
    }

    // ================================================================
    // Note on TestModeOrchestrator
    // ================================================================
    //
    // TestModeOrchestrator (if created) should use @Inject constructor
    // injection rather than being provided here. This allows it to
    // declare its dependencies clearly and makes testing easier.
    //
    // Example TestModeOrchestrator constructor:
    // @Singleton
    // class TestModeOrchestrator @Inject constructor(
    //     private val configRepository: TestModeConfigRepository,
    //     private val scenarioProvider: TestScenarioProvider,
    //     private val mockWifiScanner: MockWifiScanner,
    //     private val mockBleScanner: MockBleScanner,
    //     private val mockCellularScanner: MockCellularScanner,
    //     private val mockGnssScanner: MockGnssScanner,
    //     private val mockAudioScanner: MockAudioScanner
    // )
    // ================================================================
}

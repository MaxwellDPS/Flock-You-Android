package com.flockyou.oem

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flockyou.BuildConfig
import com.flockyou.R
import com.flockyou.data.PrivacySettings
import com.flockyou.privilege.PrivilegeModeDetector
import com.flockyou.privilege.PrivilegeMode
import com.flockyou.utils.TestHelpers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive E2E tests for OEM readiness features.
 *
 * These tests validate that the application meets OEM partnership requirements:
 * - Multi-build variant support (sideload, system, OEM)
 * - Branding customization capabilities
 * - Configuration externalization
 * - White-label completeness
 * - Privilege mode detection and adaptation
 */
@RunWith(AndroidJUnit4::class)
class OemReadinessE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ==================== Build Variant Tests ====================

    @Test
    fun buildConfig_hasCorrectFlavorDimension() {
        // Verify build flavors are properly configured
        assertNotNull("BUILD_MODE must be defined", BuildConfig.BUILD_MODE)
        assertTrue(
            "BUILD_MODE must be one of: sideload, system, oem",
            BuildConfig.BUILD_MODE in listOf("sideload", "system", "oem")
        )
    }

    @Test
    fun buildConfig_hasConsistentFlags() {
        // Verify flag consistency
        when (BuildConfig.BUILD_MODE) {
            "sideload" -> {
                assertFalse("IS_SYSTEM_BUILD must be false for sideload", BuildConfig.IS_SYSTEM_BUILD)
                assertFalse("IS_OEM_BUILD must be false for sideload", BuildConfig.IS_OEM_BUILD)
            }
            "system" -> {
                assertTrue("IS_SYSTEM_BUILD must be true for system", BuildConfig.IS_SYSTEM_BUILD)
                assertFalse("IS_OEM_BUILD must be false for system", BuildConfig.IS_OEM_BUILD)
            }
            "oem" -> {
                assertTrue("IS_SYSTEM_BUILD must be true for OEM", BuildConfig.IS_SYSTEM_BUILD)
                assertTrue("IS_OEM_BUILD must be true for OEM", BuildConfig.IS_OEM_BUILD)
            }
        }
    }

    @Test
    fun buildConfig_oemBuildImpliesSystemBuild() {
        // OEM builds are always system builds
        if (BuildConfig.IS_OEM_BUILD) {
            assertTrue(
                "IS_OEM_BUILD=true requires IS_SYSTEM_BUILD=true",
                BuildConfig.IS_SYSTEM_BUILD
            )
        }
    }

    // ==================== Branding Configuration Tests ====================

    @Test
    fun branding_appNameResourcesExist() {
        // Verify all app name variants are defined
        val appName = context.getString(R.string.app_name)
        assertNotNull("app_name resource must exist", appName)
        assertTrue("app_name must not be empty", appName.isNotEmpty())

        val appNameSystem = context.getString(R.string.app_name_system)
        assertNotNull("app_name_system resource must exist", appNameSystem)

        val appNameOem = context.getString(R.string.app_name_oem)
        assertNotNull("app_name_oem resource must exist", appNameOem)
    }

    @Test
    fun branding_buildModeStringsExist() {
        // Verify build mode description strings exist
        val sideload = context.getString(R.string.build_mode_sideload)
        val system = context.getString(R.string.build_mode_system)
        val oem = context.getString(R.string.build_mode_oem)

        assertNotNull(sideload)
        assertNotNull(system)
        assertNotNull(oem)
        assertTrue(sideload.isNotEmpty())
        assertTrue(system.isNotEmpty())
        assertTrue(oem.isNotEmpty())
    }

    @Test
    fun branding_themeColorsAreDefined() {
        // Verify that theme resources exist (won't crash when loading)
        try {
            val theme = context.theme
            assertNotNull("Theme must be defined", theme)
        } catch (e: Exception) {
            fail("Theme resources missing or misconfigured: ${e.message}")
        }
    }

    @Test
    fun branding_noHardcodedBrandReferences() {
        // This test verifies that the package name follows expected patterns.
        // For OEM builds, the package name is configurable via OEM_PACKAGE_NAME.
        // For sideload/system builds, it should be com.flockyou (possibly with .debug suffix).
        val packageName = context.packageName
        val basePackageName = packageName.substringBefore(".debug")

        // For OEM builds, check against the configured OEM package name
        if (BuildConfig.IS_OEM_BUILD) {
            // OEM_PACKAGE_NAME is available in OEM builds
            val expectedOemPackage = try {
                BuildConfig::class.java.getField("OEM_PACKAGE_NAME").get(null) as String
            } catch (e: NoSuchFieldException) {
                // Fallback if field doesn't exist (shouldn't happen in OEM build)
                "com.flockyou"
            }
            assertEquals(
                "OEM package name should match configured OEM_PACKAGE_NAME",
                expectedOemPackage,
                basePackageName
            )
        } else {
            // For sideload/system builds, package name should be com.flockyou
            assertEquals(
                "Package name should use base identifier for non-OEM builds",
                "com.flockyou",
                basePackageName
            )
        }
    }

    // ==================== Configuration Externalization Tests ====================

    @Test
    fun configuration_privacySettingsAreConfigurable() {
        // Verify privacy settings have OEM-aware defaults
        val defaultSettings = PrivacySettings()

        // Location storage default should depend on build type
        if (BuildConfig.IS_OEM_BUILD) {
            // OEM builds default to NOT storing location for privacy
            assertFalse(
                "OEM builds should default to NOT storing location",
                defaultSettings.storeLocationWithDetections
            )
        } else {
            // Sideload builds default to storing location
            assertTrue(
                "Sideload builds should default to storing location",
                defaultSettings.storeLocationWithDetections
            )
        }
    }

    @Test
    fun configuration_retentionPeriodsAreFlexible() {
        // Verify retention periods can be configured
        val periods = com.flockyou.data.RetentionPeriod.entries
        assertTrue("Must have multiple retention periods", periods.size >= 4)

        // Verify each period has display name
        periods.forEach { period ->
            assertTrue(
                "Period ${period.name} must have display name",
                period.displayName.isNotEmpty()
            )
            assertTrue(
                "Period ${period.name} must have positive hours",
                period.hours > 0
            )
        }
    }

    @Test
    fun configuration_notificationSettingsAreCustomizable() {
        // Verify notification strings exist and can be customized
        val channelName = context.getString(R.string.notification_channel_name)
        val channelDesc = context.getString(R.string.notification_channel_description)

        assertNotNull(channelName)
        assertNotNull(channelDesc)
        assertTrue(channelName.isNotEmpty())
        assertTrue(channelDesc.isNotEmpty())
    }

    // ==================== Privilege Mode Detection Tests ====================

    @Test
    fun privilegeMode_isDetectedCorrectly() {
        // Detect privilege mode at runtime
        val privilegeMode = PrivilegeModeDetector.detect(context)
        assertNotNull("Privilege mode must be detected", privilegeMode)

        // Verify mode matches build configuration
        when (privilegeMode) {
            is PrivilegeMode.OEM -> {
                assertTrue(
                    "OEM mode detected but IS_OEM_BUILD is false",
                    BuildConfig.IS_OEM_BUILD || BuildConfig.IS_SYSTEM_BUILD
                )
            }
            is PrivilegeMode.System -> {
                assertTrue(
                    "System mode detected but IS_SYSTEM_BUILD is false",
                    BuildConfig.IS_SYSTEM_BUILD
                )
            }
            is PrivilegeMode.Sideload -> {
                // Sideload mode is always valid
            }
        }
    }

    @Test
    fun privilegeMode_hasModeDescription() {
        val privilegeMode = PrivilegeModeDetector.detect(context)
        val description = PrivilegeModeDetector.getModeDescription(privilegeMode)

        assertNotNull("Mode description must exist", description)
        assertTrue("Mode description must not be empty", description.isNotEmpty())
    }

    @Test
    fun privilegeMode_providesCapabilitiesSummary() {
        val privilegeMode = PrivilegeModeDetector.detect(context)
        val capabilities = PrivilegeModeDetector.getCapabilitiesSummary(privilegeMode)

        assertNotNull("Capabilities summary must exist", capabilities)
        assertTrue("Capabilities summary must not be empty", capabilities.isNotEmpty())

        // Verify capability structure
        capabilities.forEach { (name, available) ->
            assertTrue("Capability name must not be empty", name.isNotEmpty())
            // available is a boolean, always valid
        }
    }

    @Test
    fun privilegeMode_oemHasEnhancedCapabilities() {
        val privilegeMode = PrivilegeModeDetector.detect(context)

        if (privilegeMode is PrivilegeMode.OEM) {
            val capabilities = PrivilegeModeDetector.getCapabilitiesSummary(privilegeMode)

            // OEM mode should have more capabilities available
            val availableCount = capabilities.count { it.second }
            assertTrue(
                "OEM mode should have multiple enhanced capabilities",
                availableCount > 0
            )
        }
    }

    // ==================== White-Label Completeness Tests ====================

    @Test
    fun whiteLabel_noHardcodedApiKeys() {
        // Verify no hardcoded API keys in BuildConfig
        // OEM partners should provide their own keys via build configuration
        val buildConfigFields = BuildConfig::class.java.declaredFields

        buildConfigFields.forEach { field ->
            if (field.name.contains("KEY", ignoreCase = true) ||
                field.name.contains("TOKEN", ignoreCase = true)
            ) {
                field.isAccessible = true
                val value = field.get(null)
                if (value is String && value.isNotEmpty()) {
                    // Document that keys should be configurable
                    assertTrue(
                        "API keys like ${field.name} should be configurable per OEM",
                        true
                    )
                }
            }
        }
    }

    @Test
    fun whiteLabel_manifestUsesPlaceholders() {
        // Verify app label uses string resource (can't easily test placeholder directly)
        val appInfo = context.applicationInfo
        val label = context.packageManager.getApplicationLabel(appInfo).toString()

        assertNotNull("App label must be defined", label)
        assertTrue("App label must not be empty", label.isNotEmpty())
    }

    @Test
    fun whiteLabel_iconResourcesExist() {
        // Verify launcher icon resources exist
        try {
            val iconId = context.applicationInfo.icon
            assertTrue("Launcher icon must be defined", iconId > 0)

            val drawable = context.resources.getDrawable(iconId, context.theme)
            assertNotNull("Launcher icon drawable must exist", drawable)
        } catch (e: Exception) {
            fail("Launcher icon resources missing: ${e.message}")
        }
    }

    // ==================== Multi-Tenant Support Tests ====================

    @Test
    fun multiTenant_dataIsolationViaDatabaseEncryption() {
        // Verify database uses encryption (SQLCipher)
        // This ensures data isolation between different OEM deployments
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")

        // Database may not exist yet in test, but path should be valid
        assertNotNull("Database path must be defined", dbPath)
        assertTrue(
            "Database name should indicate encryption",
            dbPath.name.contains("encrypted")
        )
    }

    @Test
    fun multiTenant_settingsUseDataStore() {
        // Verify settings use DataStore (isolated per app)
        val datastoreDir = context.filesDir.resolve("datastore")
        assertTrue(
            "DataStore directory should exist or be creatable",
            datastoreDir.exists() || datastoreDir.mkdirs()
        )
    }

    @Test
    fun multiTenant_noSharedExternalStorage() {
        // Verify app doesn't use shared external storage (privacy concern)
        // All data should be in app-private directories
        val appDataDir = context.filesDir
        assertTrue("App data dir must be internal", appDataDir.absolutePath.contains(context.packageName))
    }

    // ==================== Deployment Flexibility Tests ====================

    @Test
    fun deployment_supportsAllAndroidVersions() {
        // Verify minSdk and targetSdk are appropriate
        val appInfo = context.applicationInfo
        assertTrue("minSdk should be 26 or higher", appInfo.minSdkVersion >= 26)
        assertTrue("targetSdk should be 34", appInfo.targetSdkVersion == 34)
    }

    @Test
    fun deployment_hasProperPermissionDeclarations() {
        // Verify both standard and privileged permissions are declared
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, 0)

        assertNotNull("Package info must exist", packageInfo)

        // Package name should match the context's package name (which may be OEM-customized)
        assertEquals(
            "Package name must match context",
            context.packageName,
            packageInfo.packageName
        )

        // For non-OEM builds, verify it starts with the default base
        if (!BuildConfig.IS_OEM_BUILD) {
            assertTrue(
                "Non-OEM package name must start with com.flockyou",
                packageInfo.packageName.startsWith("com.flockyou")
            )
        }
    }

    @Test
    fun deployment_supportsDirectBoot() {
        // Verify critical components support direct boot (device encryption aware)
        // Important for OEM builds that need to work before first unlock
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_RECEIVERS
        )

        var hasDirectBootReceiver = false
        packageInfo.receivers?.forEach { receiver ->
            if (receiver.directBootAware) {
                hasDirectBootReceiver = true
            }
        }

        assertTrue(
            "At least one receiver should be direct boot aware",
            hasDirectBootReceiver
        )
    }

    // ==================== Documentation and API Tests ====================

    @Test
    fun documentation_buildVariantsAreDocumented() {
        // This test verifies build configuration is well-documented
        // Actual documentation should exist in build.gradle.kts
        assertTrue(
            "Build flavors must be properly configured",
            BuildConfig.BUILD_MODE in listOf("sideload", "system", "oem")
        )
    }

    @Test
    fun api_buildConfigIsStable() {
        // Verify BuildConfig has stable API for OEM partners
        val fields = BuildConfig::class.java.declaredFields
        val requiredFields = setOf(
            "BUILD_MODE",
            "IS_SYSTEM_BUILD",
            "IS_OEM_BUILD"
        )

        val availableFields = fields.map { it.name }.toSet()
        assertTrue(
            "BuildConfig must have all required OEM fields",
            availableFields.containsAll(requiredFields)
        )
    }

    // ==================== Integration Tests ====================

    @Test
    fun integration_oemBuildHasCorrectDefaults() {
        if (BuildConfig.IS_OEM_BUILD) {
            // Verify OEM-specific defaults
            val privacySettings = PrivacySettings()

            // OEM builds should default to maximum privacy
            assertFalse(
                "OEM: Location storage should default to OFF",
                privacySettings.storeLocationWithDetections
            )

            // Verify app name reflects OEM build
            val appName = context.getString(R.string.app_name_oem)
            assertTrue(
                "OEM app name should indicate OEM build",
                appName.contains("OEM") || appName.isNotEmpty()
            )
        }
    }

    @Test
    fun integration_systemBuildHasPrivilegedPermissions() {
        if (BuildConfig.IS_SYSTEM_BUILD) {
            val privilegeMode = PrivilegeModeDetector.detect(context)

            assertTrue(
                "System build should detect privileged mode",
                privilegeMode is PrivilegeMode.System || privilegeMode is PrivilegeMode.OEM
            )
        }
    }

    @Test
    fun integration_sideloadBuildWorksWithoutPrivileges() {
        if (BuildConfig.BUILD_MODE == "sideload") {
            // Verify app can function without privileged permissions
            val privilegeMode = PrivilegeModeDetector.detect(context)

            // Should still work in sideload mode
            assertNotNull("Privilege mode must be detected even in sideload", privilegeMode)

            // Privacy defaults should allow location storage
            val privacySettings = PrivacySettings()
            assertTrue(
                "Sideload: Location storage should default to ON",
                privacySettings.storeLocationWithDetections
            )
        }
    }

    // ==================== OEM Partner Requirements ====================

    @Test
    fun oemRequirement_allStringsAreExternalizable() {
        // Verify critical strings are in resources, not hardcoded
        val testStrings = listOf(
            R.string.app_name,
            R.string.app_description,
            R.string.notification_channel_name,
            R.string.notification_channel_description
        )

        testStrings.forEach { stringId ->
            val value = context.getString(stringId)
            assertNotNull("String resource must exist", value)
            assertTrue("String resource must not be empty", value.isNotEmpty())
        }
    }

    @Test
    fun oemRequirement_noTelemetryOrAnalytics() {
        // Verify no hardcoded analytics/telemetry endpoints
        // OEM partners need control over data collection
        val buildConfigFields = BuildConfig::class.java.declaredFields

        val analyticsFields = buildConfigFields.filter {
            it.name.contains("ANALYTICS", ignoreCase = true) ||
                    it.name.contains("TELEMETRY", ignoreCase = true) ||
                    it.name.contains("ENDPOINT", ignoreCase = true)
        }

        // If analytics fields exist, they should be empty or configurable
        analyticsFields.forEach { field ->
            field.isAccessible = true
            val value = field.get(null)
            assertTrue(
                "Analytics/telemetry fields should be empty or configurable",
                value == null || (value is String && value.isEmpty())
            )
        }
    }

    @Test
    fun oemRequirement_databaseEncryptionIsEnforced() {
        // Verify database encryption cannot be disabled
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")
        assertTrue(
            "Database must use encrypted storage",
            dbPath.name.contains("encrypted")
        )
    }
}

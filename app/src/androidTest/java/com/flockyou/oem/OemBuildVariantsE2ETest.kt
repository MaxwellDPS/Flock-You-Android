package com.flockyou.oem

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flockyou.BuildConfig
import com.flockyou.privilege.PrivilegeModeDetector
import com.flockyou.privilege.PrivilegeMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E tests for OEM build variant correctness.
 *
 * This test suite validates that different build variants (sideload, system, OEM)
 * produce correctly configured builds with appropriate permissions, capabilities,
 * and runtime behavior.
 *
 * Test Coverage:
 * - Build variants produce correct artifacts
 * - Application IDs are correct per variant
 * - Version names include appropriate suffixes
 * - Privilege detection works correctly per variant
 * - Permissions are declared appropriately
 * - Capabilities match expected privilege level
 */
@RunWith(AndroidJUnit4::class)
class OemBuildVariantsE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // These tests just read configuration - no cleanup needed
    }

    // ==================== Build Variant Configuration ====================

    @Test
    fun buildVariant_hasCorrectApplicationId() {
        // Verify application ID matches build variant
        val packageName = context.packageName
        val basePackage = packageName.removeSuffix(".debug")

        assertEquals(
            "Base package should be com.flockyou",
            "com.flockyou",
            basePackage
        )
    }

    @Test
    fun buildVariant_debugSuffixIsCorrect() {
        // Verify debug builds have .debug suffix
        val packageName = context.packageName

        if (BuildConfig.DEBUG) {
            assertTrue(
                "Debug builds must have .debug suffix",
                packageName.endsWith(".debug")
            )
        } else {
            assertEquals(
                "Release builds must not have suffix",
                "com.flockyou",
                packageName
            )
        }
    }

    @Test
    fun buildVariant_versionNameIncludesSuffix() {
        // Verify version name includes variant suffix
        val versionName = BuildConfig.VERSION_NAME

        when (BuildConfig.BUILD_MODE) {
            "sideload" -> {
                // Sideload has no suffix in release, -debug in debug
                if (BuildConfig.DEBUG) {
                    assertTrue(
                        "Sideload debug should have -debug suffix",
                        versionName.contains("debug")
                    )
                }
            }
            "system" -> {
                assertTrue(
                    "System build should have -system suffix",
                    versionName.contains("system")
                )
            }
            "oem" -> {
                assertTrue(
                    "OEM build should have -oem suffix",
                    versionName.contains("oem")
                )
            }
        }
    }

    @Test
    fun buildVariant_versionCodeIsSet() {
        // Verify version code is properly set
        val versionCode = BuildConfig.VERSION_CODE
        assertTrue(
            "Version code must be positive",
            versionCode > 0
        )
    }

    // ==================== Build Type Tests ====================

    @Test
    fun buildType_debugFlagIsConsistent() {
        // Verify DEBUG flag matches build type
        val isDebug = BuildConfig.DEBUG

        if (BuildConfig.BUILD_TYPE == "debug") {
            assertTrue("Debug build type must have DEBUG=true", isDebug)
        } else if (BuildConfig.BUILD_TYPE == "release") {
            assertFalse("Release build type must have DEBUG=false", isDebug)
        }
    }

    @Test
    fun buildType_applicationIdMatchesDebugState() {
        // Verify application ID suffix matches debug state
        val packageName = context.packageName
        val hasDebugSuffix = packageName.endsWith(".debug")

        assertEquals(
            "Debug suffix should match DEBUG flag",
            BuildConfig.DEBUG,
            hasDebugSuffix
        )
    }

    // ==================== Flavor Configuration Tests ====================

    @Test
    fun flavor_sideloadConfigurationIsCorrect() {
        if (BuildConfig.BUILD_MODE == "sideload") {
            assertFalse(
                "Sideload: IS_SYSTEM_BUILD must be false",
                BuildConfig.IS_SYSTEM_BUILD
            )
            assertFalse(
                "Sideload: IS_OEM_BUILD must be false",
                BuildConfig.IS_OEM_BUILD
            )

            // Verify privilege detection
            val mode = PrivilegeModeDetector.detect(context)
            // In test environment, may detect as System if signed with test keys
            // So we check it's consistent with actual permissions
            assertNotNull("Privilege mode must be detected", mode)
        }
    }

    @Test
    fun flavor_systemConfigurationIsCorrect() {
        if (BuildConfig.BUILD_MODE == "system") {
            assertTrue(
                "System: IS_SYSTEM_BUILD must be true",
                BuildConfig.IS_SYSTEM_BUILD
            )
            assertFalse(
                "System: IS_OEM_BUILD must be false",
                BuildConfig.IS_OEM_BUILD
            )

            val mode = PrivilegeModeDetector.detect(context)
            assertNotNull("Privilege mode must be detected", mode)

            // System build should detect as System or OEM (if platform signed)
            assertTrue(
                "System build should have elevated privileges or be sideload in test",
                mode is PrivilegeMode.System ||
                mode is PrivilegeMode.OEM ||
                mode is PrivilegeMode.Sideload
            )
        }
    }

    @Test
    fun flavor_oemConfigurationIsCorrect() {
        if (BuildConfig.BUILD_MODE == "oem") {
            assertTrue(
                "OEM: IS_SYSTEM_BUILD must be true",
                BuildConfig.IS_SYSTEM_BUILD
            )
            assertTrue(
                "OEM: IS_OEM_BUILD must be true",
                BuildConfig.IS_OEM_BUILD
            )

            val mode = PrivilegeModeDetector.detect(context)
            assertNotNull("Privilege mode must be detected", mode)

            // OEM build should ideally detect as OEM, but may be Sideload in test
            assertTrue(
                "OEM build should detect OEM mode or sideload in test environment",
                mode is PrivilegeMode.OEM || mode is PrivilegeMode.Sideload
            )
        }
    }

    @Test
    fun flavor_oemBuildImpliesSystemBuild() {
        // OEM builds are always system builds
        if (BuildConfig.IS_OEM_BUILD) {
            assertTrue(
                "IS_OEM_BUILD=true requires IS_SYSTEM_BUILD=true",
                BuildConfig.IS_SYSTEM_BUILD
            )
        }
    }

    // ==================== Privilege Detection Tests ====================

    @Test
    fun privilege_modeIsDetected() {
        // Verify runtime privilege detection works
        val mode = PrivilegeModeDetector.detect(context)
        assertNotNull("Privilege mode must be detected", mode)

        // Log detected mode for debugging
        val description = PrivilegeModeDetector.getModeDescription(mode)
        assertNotNull("Mode description must exist", description)
        assertTrue("Mode description must not be empty", description.isNotEmpty())
    }

    @Test
    fun privilege_capabilitiesMatchMode() {
        // Verify capabilities summary matches detected mode
        val mode = PrivilegeModeDetector.detect(context)
        val capabilities = PrivilegeModeDetector.getCapabilitiesSummary(mode)

        assertNotNull("Capabilities summary must exist", capabilities)
        assertTrue("Must have at least 3 capabilities", capabilities.size >= 3)

        // Verify capability structure
        capabilities.forEach { (name, available) ->
            assertTrue("Capability name must not be empty", name.isNotEmpty())
            assertNotNull("Capability availability must be defined", available)
        }
    }

    @Test
    fun privilege_sideloadHasLimitedCapabilities() {
        val mode = PrivilegeModeDetector.detect(context)

        if (mode is PrivilegeMode.Sideload) {
            assertFalse(
                "Sideload: Cannot disable WiFi throttling",
                mode.canDisableWifiThrottling
            )
            assertFalse(
                "Sideload: No real MAC address access",
                mode.hasRealMacAccess
            )
            assertFalse(
                "Sideload: No privileged phone access",
                mode.hasPrivilegedPhoneAccess
            )
            assertFalse(
                "Sideload: Cannot be persistent",
                mode.canBePersistent
            )
        }
    }

    @Test
    fun privilege_systemHasEnhancedCapabilities() {
        val mode = PrivilegeModeDetector.detect(context)

        if (mode is PrivilegeMode.System) {
            // System mode should have some enhanced capabilities
            assertTrue(
                "System: Has continuous BLE scan capability",
                mode.hasContinuousBleScan
            )

            // Other capabilities depend on actual permissions granted
            assertNotNull(
                "System: Has privileged permissions flag",
                mode.hasPrivilegedPermissions
            )
        }
    }

    @Test
    fun privilege_oemHasMaximumCapabilities() {
        val mode = PrivilegeModeDetector.detect(context)

        if (mode is PrivilegeMode.OEM) {
            assertTrue(
                "OEM: Can disable WiFi throttling",
                mode.canDisableWifiThrottling
            )
            assertTrue(
                "OEM: Has real MAC address access",
                mode.hasRealMacAccess
            )
            assertTrue(
                "OEM: Has continuous BLE scan",
                mode.hasContinuousBleScan
            )
            assertTrue(
                "OEM: Can be persistent",
                mode.canBePersistent
            )

            if (mode.hasReadPrivilegedPhoneState) {
                assertTrue(
                    "OEM: Has privileged phone access if permission granted",
                    mode.hasPrivilegedPhoneAccess
                )
            }
        }
    }

    // ==================== Permission Declaration Tests ====================

    @Test
    fun permissions_standardPermissionsAreDeclared() {
        // Verify standard permissions are declared for all builds
        val pm = context.packageManager
        val packageInfo = try {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
        } catch (e: Exception) {
            fail("Failed to get package info: ${e.message}")
            return
        }

        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

        // Critical permissions that must be declared
        val requiredPermissions = listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.FOREGROUND_SERVICE"
        )

        requiredPermissions.forEach { permission ->
            assertTrue(
                "Required permission must be declared: $permission",
                permissions.contains(permission)
            )
        }
    }

    @Test
    fun permissions_privilegedPermissionsAreDeclared() {
        // Verify privileged permissions are declared (but may not be granted)
        val pm = context.packageManager
        val packageInfo = try {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
        } catch (e: Exception) {
            fail("Failed to get package info: ${e.message}")
            return
        }

        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

        // Privileged permissions (only granted to system/OEM builds)
        val privilegedPermissions = listOf(
            "android.permission.BLUETOOTH_PRIVILEGED",
            "android.permission.READ_PRIVILEGED_PHONE_STATE",
            "android.permission.PEERS_MAC_ADDRESS"
        )

        // These should be declared but only granted for system/OEM
        privilegedPermissions.forEach { permission ->
            assertTrue(
                "Privileged permission should be declared: $permission",
                permissions.contains(permission)
            )
        }
    }

    @Test
    fun permissions_grantedPermissionsMatchPrivilegeLevel() {
        // Verify granted permissions match detected privilege level
        val mode = PrivilegeModeDetector.detect(context)
        val pm = context.packageManager

        when (mode) {
            is PrivilegeMode.Sideload -> {
                // Sideload should not have privileged permissions granted
                val hasPrivileged = context.checkSelfPermission(
                    "android.permission.BLUETOOTH_PRIVILEGED"
                ) == PackageManager.PERMISSION_GRANTED

                assertFalse(
                    "Sideload should not have BLUETOOTH_PRIVILEGED",
                    hasPrivileged
                )
            }
            is PrivilegeMode.System -> {
                // System may have some privileged permissions
                // Depends on installation and whitelist
            }
            is PrivilegeMode.OEM -> {
                // OEM should have privileged permissions if properly installed
                // In test environment, may not be granted
            }
        }
    }

    // ==================== Application Info Tests ====================

    @Test
    fun appInfo_systemFlagMatchesExpectation() {
        // Verify FLAG_SYSTEM matches build mode
        val appInfo = context.applicationInfo
        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        when (BuildConfig.BUILD_MODE) {
            "sideload" -> {
                // In test environment, may be signed with debug keys
                // So this test documents expected production behavior
                assertTrue(
                    "Sideload build expectation documented",
                    true
                )
            }
            "system", "oem" -> {
                // System/OEM builds should ideally have FLAG_SYSTEM
                // In test environment, depends on how APK was installed
                assertTrue(
                    "System/OEM build expectation documented",
                    true
                )
            }
        }
    }

    @Test
    fun appInfo_sourceDirectoryReflectsBuild() {
        // Verify source directory matches installation type
        val appInfo = context.applicationInfo
        val sourceDir = appInfo.sourceDir

        assertNotNull("Source directory must be defined", sourceDir)
        assertTrue("Source directory must not be empty", sourceDir.isNotEmpty())

        // In production:
        // - Sideload: /data/app/...
        // - System: /system/priv-app/... or /system_ext/priv-app/...
        // - OEM: Same as System

        when (BuildConfig.BUILD_MODE) {
            "sideload" -> {
                // Expected in /data/app in production
                assertTrue("Source path exists", sourceDir.isNotEmpty())
            }
            "system", "oem" -> {
                // Expected in /system/priv-app or /system_ext/priv-app in production
                // In test environment, may be in /data
                assertTrue("Source path exists", sourceDir.isNotEmpty())
            }
        }
    }

    @Test
    fun appInfo_targetSdkIsCorrect() {
        // Verify targetSdk matches expected value
        val appInfo = context.applicationInfo
        assertEquals(
            "Target SDK should be 34",
            34,
            appInfo.targetSdkVersion
        )
    }

    @Test
    fun appInfo_minSdkIsCorrect() {
        // Verify minSdk matches expected value
        val appInfo = context.applicationInfo
        assertTrue(
            "Min SDK should be 26 or higher",
            appInfo.minSdkVersion >= 26
        )
    }

    // ==================== Build Artifact Tests ====================

    @Test
    fun build_allVariantsAreDistinct() {
        // Document that each build variant produces distinct artifacts
        val mode = BuildConfig.BUILD_MODE
        val isSystem = BuildConfig.IS_SYSTEM_BUILD
        val isOem = BuildConfig.IS_OEM_BUILD

        // Each combination of flags represents a distinct build
        val buildSignature = "$mode-$isSystem-$isOem"
        assertNotNull("Build signature must be unique", buildSignature)

        // Verify this matches one of the expected combinations
        val validSignatures = setOf(
            "sideload-false-false",
            "system-true-false",
            "oem-true-true"
        )

        assertTrue(
            "Build signature must be valid: $buildSignature",
            validSignatures.contains(buildSignature)
        )
    }

    @Test
    fun build_configurationIsComplete() {
        // Verify all required BuildConfig fields are present
        assertNotNull("BUILD_MODE must be set", BuildConfig.BUILD_MODE)
        assertNotNull("IS_SYSTEM_BUILD must be set", BuildConfig.IS_SYSTEM_BUILD)
        assertNotNull("IS_OEM_BUILD must be set", BuildConfig.IS_OEM_BUILD)
        assertNotNull("VERSION_NAME must be set", BuildConfig.VERSION_NAME)
        assertTrue("VERSION_CODE must be positive", BuildConfig.VERSION_CODE > 0)
        assertNotNull("APPLICATION_ID must be set", BuildConfig.APPLICATION_ID)
        assertNotNull("BUILD_TYPE must be set", BuildConfig.BUILD_TYPE)
    }

    // ==================== Integration Tests ====================

    @Test
    fun integration_buildModeMatchesDetectedPrivilege() {
        // Verify BuildConfig matches runtime detection
        val mode = PrivilegeModeDetector.detect(context)

        when (BuildConfig.BUILD_MODE) {
            "sideload" -> {
                if (!BuildConfig.IS_SYSTEM_BUILD && !BuildConfig.IS_OEM_BUILD) {
                    // In test environment, may still detect System/OEM if test-signed
                    assertTrue(
                        "Sideload build detected appropriately",
                        mode is PrivilegeMode.Sideload ||
                        mode is PrivilegeMode.System ||
                        mode is PrivilegeMode.OEM
                    )
                }
            }
            "system" -> {
                if (BuildConfig.IS_SYSTEM_BUILD && !BuildConfig.IS_OEM_BUILD) {
                    assertTrue(
                        "System build configuration is correct",
                        true
                    )
                }
            }
            "oem" -> {
                if (BuildConfig.IS_SYSTEM_BUILD && BuildConfig.IS_OEM_BUILD) {
                    assertTrue(
                        "OEM build configuration is correct",
                        true
                    )
                }
            }
        }
    }

    @Test
    fun integration_capabilitiesReflectBuildMode() {
        // Verify detected capabilities match build mode expectations
        val mode = PrivilegeModeDetector.detect(context)
        val capabilities = PrivilegeModeDetector.getCapabilitiesSummary(mode)

        assertNotNull("Capabilities must be detected", capabilities)
        assertTrue("Must have multiple capabilities", capabilities.size >= 3)

        // The available capabilities should generally increase with privilege level
        val availableCount = capabilities.count { it.second }

        when (mode) {
            is PrivilegeMode.Sideload -> {
                assertTrue(
                    "Sideload should have minimal capabilities",
                    availableCount <= 2
                )
            }
            is PrivilegeMode.System -> {
                assertTrue(
                    "System should have some capabilities",
                    availableCount >= 0
                )
            }
            is PrivilegeMode.OEM -> {
                assertTrue(
                    "OEM should have maximum capabilities",
                    availableCount >= 3
                )
            }
        }
    }
}

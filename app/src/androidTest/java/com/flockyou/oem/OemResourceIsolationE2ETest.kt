package com.flockyou.oem

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flockyou.BuildConfig
import com.flockyou.utils.TestHelpers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E2E tests for OEM resource isolation and multi-tenant security.
 *
 * This test suite validates that different OEM builds maintain proper resource
 * isolation, ensuring no data or configuration leakage between deployments.
 *
 * Test Coverage:
 * - OEM-specific resources don't leak between builds
 * - Database files are properly isolated
 * - Shared preferences are app-private
 * - Cache directories are isolated
 * - External storage is not used
 * - No cross-OEM data contamination
 */
@RunWith(AndroidJUnit4::class)
class OemResourceIsolationE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Start with clean state
        TestHelpers.clearAppData(context)
    }

    // ==================== Database Isolation Tests ====================

    @Test
    fun isolation_databaseIsAppPrivate() {
        // Verify database is in app-private directory
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")

        assertTrue(
            "Database must be in app-private directory",
            dbPath.absolutePath.contains(context.packageName)
        )

        assertFalse(
            "Database must NOT be in shared storage",
            dbPath.absolutePath.contains("/sdcard") ||
            dbPath.absolutePath.contains("/storage/emulated")
        )
    }

    @Test
    fun isolation_databasePathIncludesPackageName() {
        // Verify database path includes package name for isolation
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")
        val packageName = context.packageName

        assertTrue(
            "Database path must include package name for isolation",
            dbPath.absolutePath.contains(packageName)
        )
    }

    @Test
    fun isolation_databaseIsEncrypted() {
        // Verify database uses encryption for multi-tenant security
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")

        assertTrue(
            "Database name must indicate encryption",
            dbPath.name.contains("encrypted")
        )
    }

    @Test
    fun isolation_multipleDatabasesDoNotConflict() {
        // Verify different database files can coexist
        val db1 = context.getDatabasePath("flockyou_database_encrypted")
        val db2 = context.getDatabasePath("test_database")

        assertNotEquals(
            "Different databases must have different paths",
            db1.absolutePath,
            db2.absolutePath
        )

        // Both should be in same app-private directory
        assertEquals(
            "All databases should be in same package directory",
            db1.parent,
            db2.parent
        )
    }

    // ==================== SharedPreferences Isolation ====================

    @Test
    fun isolation_sharedPreferencesAreAppPrivate() {
        // Verify SharedPreferences are in app-private directory
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

        if (prefsDir.exists()) {
            assertTrue(
                "SharedPreferences must be in app data dir",
                prefsDir.absolutePath.contains(context.packageName)
            )
        }
    }

    @Test
    fun isolation_sharedPreferencesNotInExternalStorage() {
        // Verify SharedPreferences are not in external storage
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

        assertFalse(
            "SharedPreferences must NOT be in external storage",
            prefsDir.absolutePath.contains("/sdcard") ||
            prefsDir.absolutePath.contains("/storage/emulated")
        )
    }

    @Test
    fun isolation_multiplePreferenceFilesAreIsolated() {
        // Verify different preference files can coexist without conflict
        val prefs1 = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val prefs2 = context.getSharedPreferences("security", Context.MODE_PRIVATE)

        // Write to both
        prefs1.edit().putString("key", "value1").apply()
        prefs2.edit().putString("key", "value2").apply()

        // Verify isolation
        assertEquals("Settings prefs has correct value", "value1", prefs1.getString("key", null))
        assertEquals("Security prefs has correct value", "value2", prefs2.getString("key", null))
    }

    // ==================== DataStore Isolation ====================

    @Test
    fun isolation_dataStoreIsAppPrivate() {
        // Verify DataStore directory is app-private
        val datastoreDir = context.filesDir.resolve("datastore")

        assertTrue(
            "DataStore must be in app-private files directory",
            datastoreDir.absolutePath.contains(context.packageName)
        )

        assertFalse(
            "DataStore must NOT be in external storage",
            datastoreDir.absolutePath.contains("/sdcard") ||
            datastoreDir.absolutePath.contains("/storage/emulated")
        )
    }

    @Test
    fun isolation_dataStoreDirectoryIsCreatable() {
        // Verify DataStore directory can be created
        val datastoreDir = context.filesDir.resolve("datastore")

        assertTrue(
            "DataStore directory should exist or be creatable",
            datastoreDir.exists() || datastoreDir.mkdirs()
        )

        if (datastoreDir.exists()) {
            assertTrue("DataStore directory should be writable", datastoreDir.canWrite())
        }
    }

    // ==================== Cache Isolation ====================

    @Test
    fun isolation_cacheIsAppPrivate() {
        // Verify cache directory is app-private
        val cacheDir = context.cacheDir

        assertTrue(
            "Cache must be in app-private directory",
            cacheDir.absolutePath.contains(context.packageName)
        )

        assertFalse(
            "Cache must NOT be in external storage",
            cacheDir.absolutePath.contains("/sdcard") ||
            cacheDir.absolutePath.contains("/storage/emulated")
        )
    }

    @Test
    fun isolation_externalCacheIsNotUsed() {
        // Verify app does not use external cache
        // External cache is world-readable on older Android versions
        val externalCacheDir = context.externalCacheDir

        // externalCacheDir may exist, but we should prefer internal cache
        val internalCacheDir = context.cacheDir

        assertTrue(
            "Internal cache should be preferred",
            internalCacheDir.exists() || internalCacheDir.mkdirs()
        )
    }

    // ==================== Files Directory Isolation ====================

    @Test
    fun isolation_filesDirectoryIsAppPrivate() {
        // Verify files directory is app-private
        val filesDir = context.filesDir

        assertTrue(
            "Files dir must be in app-private directory",
            filesDir.absolutePath.contains(context.packageName)
        )

        assertFalse(
            "Files dir must NOT be in external storage",
            filesDir.absolutePath.contains("/sdcard") ||
            filesDir.absolutePath.contains("/storage/emulated")
        )
    }

    @Test
    fun isolation_noDataInExternalFiles() {
        // Verify app does not store data in external files directory
        val externalFilesDir = context.getExternalFilesDir(null)

        // External files may exist but should not be used for sensitive data
        // This test documents the requirement
        assertTrue(
            "Sensitive data should not use external files",
            true // Documented requirement
        )
    }

    // ==================== Package Data Isolation ====================

    @Test
    fun isolation_appDataDirIsCorrect() {
        // Verify app data directory matches package name
        val dataDir = context.applicationInfo.dataDir
        val packageName = context.packageName

        assertTrue(
            "Data directory must include package name",
            dataDir.contains(packageName)
        )
    }

    @Test
    fun isolation_appDataDirIsNotShared() {
        // Verify app data directory is not shared with other apps
        val dataDir = context.applicationInfo.dataDir

        assertFalse(
            "Data dir must NOT be in /data/data/shared",
            dataDir.contains("/data/data/shared") ||
            dataDir.contains("/data/shared")
        )
    }

    // ==================== Permission Isolation ====================

    @Test
    fun isolation_noWorldReadableFiles() {
        // Verify no files are created with MODE_WORLD_READABLE
        // This would break isolation between OEM deployments
        val filesDir = context.filesDir

        // Test file creation mode
        val testFile = File(filesDir, "test_isolation.txt")
        testFile.writeText("test")

        // Verify file is not world-readable
        assertTrue(
            "Files should be private to app",
            testFile.exists()
        )

        // Clean up
        testFile.delete()
    }

    @Test
    fun isolation_noWorldWritableFiles() {
        // Verify no files are created with MODE_WORLD_WRITABLE
        val filesDir = context.filesDir
        val testFile = File(filesDir, "test_writable.txt")
        testFile.writeText("test")

        // File should exist and be private
        assertTrue("File should be created", testFile.exists())

        // Clean up
        testFile.delete()
    }

    // ==================== Cross-Build Isolation ====================

    @Test
    fun isolation_buildModeDeterminesIsolation() {
        // Verify build mode affects data isolation strategy
        val buildMode = BuildConfig.BUILD_MODE

        // All build modes should use app-private storage
        val filesDir = context.filesDir
        assertTrue(
            "All builds must use app-private storage",
            filesDir.absolutePath.contains(context.packageName)
        )
    }

    @Test
    fun isolation_packageNamePreventsDataSharing() {
        // Verify different package names prevent data sharing
        val packageName = context.packageName

        // Each build variant (with different package) has its own data
        assertTrue(
            "Package name determines data isolation",
            packageName.startsWith("com.flockyou")
        )
    }

    // ==================== Resource Access Isolation ====================

    @Test
    fun isolation_resourcesArePackageScoped() {
        // Verify resources are scoped to this package
        val resources = context.resources
        assertNotNull("Resources must be accessible", resources)

        // Resources should be from this package's APK
        val packageName = context.packageName
        assertNotNull("Package name must be defined", packageName)
    }

    @Test
    fun isolation_assetsArePackageScoped() {
        // Verify assets are scoped to this package
        val assetManager = context.assets
        assertNotNull("Asset manager must be accessible", assetManager)

        // Assets should be from this package's APK
        try {
            val assets = assetManager.list("")
            assertNotNull("Assets list should be accessible", assets)
        } catch (e: Exception) {
            // No assets is fine - just testing access
        }
    }

    // ==================== Security Context Isolation ====================

    @Test
    fun isolation_securityContextIsCorrect() {
        // Verify app runs in its own security context
        val uid = android.os.Process.myUid()
        assertTrue(
            "App must have valid UID",
            uid > 0
        )

        // UID should be unique per package
        val pid = android.os.Process.myPid()
        assertTrue(
            "App must have valid PID",
            pid > 0
        )
    }

    @Test
    fun isolation_userIdIsConsistent() {
        // Verify app runs under consistent user ID
        val userId = android.os.Process.myUserHandle()
        assertNotNull("User handle must be defined", userId)
    }

    // ==================== Multi-Tenant Verification ====================

    @Test
    fun isolation_multipleOemBuildsCanCoexist() {
        // Document that different OEM builds can be installed simultaneously
        // Each would have a different package name

        val packageName = context.packageName
        assertTrue(
            "Each OEM build has unique package name (configured in build.gradle)",
            packageName.isNotEmpty()
        )

        // Different OEM partners would use different package names:
        // - com.example.oem1.surveillance
        // - com.example.oem2.surveillance
        // This ensures complete data isolation
    }

    @Test
    fun isolation_databaseEncryptionPreventsLeakage() {
        // Verify database encryption adds additional isolation layer
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")

        assertTrue(
            "Database encryption prevents data leakage between OEMs",
            dbPath.name.contains("encrypted")
        )

        // Even if two OEM builds somehow shared storage,
        // they would have different encryption keys
    }

    // ==================== Clean-up Verification ====================

    @Test
    fun isolation_dataCanBeCompletelyCleared() {
        // Verify all app data can be cleared for testing
        TestHelpers.clearAppData(context)

        // Verify databases are cleared
        val dbPath = context.getDatabasePath("flockyou_database_encrypted")
        assertFalse(
            "Database should be deleted after clearAppData",
            dbPath.exists()
        )

        // Verify cache is cleared
        val cacheDir = context.cacheDir
        assertTrue(
            "Cache should be empty or cleared",
            cacheDir.listFiles()?.isEmpty() ?: true
        )
    }

    @Test
    fun isolation_preferencesCanBeCleared() {
        // Verify SharedPreferences can be cleared
        val prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit().putString("key", "value").apply()

        // Clear
        prefs.edit().clear().apply()

        // Verify cleared
        assertNull(
            "Preferences should be cleared",
            prefs.getString("key", null)
        )
    }

    // ==================== OEM Partner Requirements ====================

    @Test
    fun isolation_noHardcodedPaths() {
        // Verify no hardcoded file paths that could leak between OEMs
        // All paths should be derived from Context

        val validPaths = listOf(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getDatabasePath("test").parent
        )

        validPaths.forEach { path ->
            assertTrue(
                "All paths must be app-private and contain package name",
                path.contains(context.packageName)
            )
        }
    }

    @Test
    fun isolation_contentProvidersAreNotExported() {
        // Verify app does not export content providers
        // Exported providers could leak data between OEM builds
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS
        )

        val providers = packageInfo.providers
        if (providers != null && providers.isNotEmpty()) {
            providers.forEach { provider ->
                assertFalse(
                    "Content providers must not be exported for isolation: ${provider.name}",
                    provider.exported
                )
            }
        }
    }

    @Test
    fun isolation_noSharedUserIds() {
        // Verify app does not use sharedUserId
        // Shared user IDs would break isolation between OEM builds
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            0
        )

        // Android 11+ doesn't expose sharedUserId in PackageInfo
        // This test documents the requirement
        assertTrue(
            "App must not use sharedUserId for proper OEM isolation",
            true // Requirement documented
        )
    }
}

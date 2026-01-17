package com.flockyou.rule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Rule to grant all necessary permissions for testing.
 */
val grantPermissionsRule: GrantPermissionRule = GrantPermissionRule.grant(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.BLUETOOTH,
    Manifest.permission.BLUETOOTH_ADMIN,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS
)

/**
 * Rule that disables animations during tests for faster and more reliable execution.
 */
class DisableAnimationsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                // Disable animations
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "settings put global window_animation_scale 0"
                )
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "settings put global transition_animation_scale 0"
                )
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "settings put global animator_duration_scale 0"
                )
                
                try {
                    base.evaluate()
                } finally {
                    // Re-enable animations
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                        "settings put global window_animation_scale 1"
                    )
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                        "settings put global transition_animation_scale 1"
                    )
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                        "settings put global animator_duration_scale 1"
                    )
                }
            }
        }
    }
}

/**
 * Rule that clears app data before each test.
 */
class ClearDataRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                context.deleteDatabase("flockyou_database")
                context.getSharedPreferences("flockyou_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                
                base.evaluate()
            }
        }
    }
}

/**
 * Rule that retries flaky tests.
 */
class RetryRule(private val retryCount: Int = 3) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                var lastThrowable: Throwable? = null
                
                repeat(retryCount) { attempt ->
                    try {
                        base.evaluate()
                        return // Test passed
                    } catch (t: Throwable) {
                        lastThrowable = t
                        if (attempt < retryCount - 1) {
                            println("Test failed on attempt ${attempt + 1}, retrying...")
                        }
                    }
                }
                
                throw lastThrowable ?: AssertionError("Test failed without exception")
            }
        }
    }
}

/**
 * Rule that checks if required permissions are available.
 */
class PermissionCheckRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                
                val requiredPermissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                
                val missingPermissions = requiredPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                
                if (missingPermissions.isNotEmpty()) {
                    println("Warning: Missing permissions for test: $missingPermissions")
                }
                
                base.evaluate()
            }
        }
    }
}

package com.flockyou.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized manager for hardware-backed cryptographic keys.
 * Uses StrongBox (TPM) when available, with TEE fallback.
 */
@Singleton
class SecureKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    /**
     * Security level hierarchy for cryptographic keys.
     * STRONGBOX > TEE > SOFTWARE_ONLY
     */
    enum class SecurityLevel {
        /** Hardware TPM - highest security, isolated secure processor */
        STRONGBOX,
        /** Trusted Execution Environment - hardware-backed but shared processor */
        TEE,
        /** Software-only - no hardware backing available */
        SOFTWARE_ONLY
    }

    /**
     * Device security capabilities for key management.
     */
    data class KeyCapabilities(
        val maxSecurityLevel: SecurityLevel,
        val hasStrongBox: Boolean,
        val hasTEE: Boolean,
        val supportsBiometricBinding: Boolean,
        val supportsUserAuthentication: Boolean
    )

    /**
     * Configuration for key protection level.
     */
    data class KeyProtectionConfig(
        /** Require user authentication (PIN/biometric) before key use */
        val requireUserAuthentication: Boolean = false,
        /** Duration in seconds that authentication is valid (-1 = every use, 0 = per-operation) */
        val authenticationValidityDurationSeconds: Int = -1,
        /** Require specifically biometric authentication (not PIN) */
        val requireBiometric: Boolean = false,
        /** Invalidate key if new biometrics are enrolled */
        val invalidatedByBiometricEnrollment: Boolean = true,
        /** Require device to be unlocked for key access */
        val requireUnlockedDevice: Boolean = true
    )

    private var _capabilities: KeyCapabilities? = null

    /**
     * Get device security capabilities (cached after first call).
     */
    val capabilities: KeyCapabilities
        get() = _capabilities ?: detectCapabilities().also { _capabilities = it }

    /**
     * Detect hardware security capabilities of the device.
     */
    fun detectCapabilities(): KeyCapabilities {
        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else {
            false
        }

        // TEE is available on most devices with Android 6.0+ (API 23)
        val hasTEE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

        val maxLevel = when {
            hasStrongBox -> SecurityLevel.STRONGBOX
            hasTEE -> SecurityLevel.TEE
            else -> SecurityLevel.SOFTWARE_ONLY
        }

        val caps = KeyCapabilities(
            maxSecurityLevel = maxLevel,
            hasStrongBox = hasStrongBox,
            hasTEE = hasTEE,
            supportsBiometricBinding = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            supportsUserAuthentication = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        )

        Log.d(TAG, "Device security capabilities: $caps")
        return caps
    }

    /**
     * Check if a specific key is hardware-backed (StrongBox or TEE).
     */
    fun isKeyHardwareBacked(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(alias, null) as? SecretKey ?: return false
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                        keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking key hardware backing for alias: $alias", e)
            false
        }
    }

    /**
     * Get the security level of an existing key.
     */
    fun getKeySecurityLevel(alias: String): SecurityLevel {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(alias, null) as? SecretKey
                ?: return SecurityLevel.SOFTWARE_ONLY
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (keyInfo.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE
                    else -> SecurityLevel.SOFTWARE_ONLY
                }
            } else {
                @Suppress("DEPRECATION")
                if (keyInfo.isInsideSecureHardware) SecurityLevel.TEE else SecurityLevel.SOFTWARE_ONLY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting key security level for alias: $alias", e)
            SecurityLevel.SOFTWARE_ONLY
        }
    }

    /**
     * Create a hardware-backed AES key with StrongBox if available, TEE fallback.
     *
     * @param alias Unique identifier for the key in the Android Keystore
     * @param config Protection configuration for the key
     * @return The created SecretKey
     * @throws Exception if key creation fails
     */
    fun createHardwareBackedKey(
        alias: String,
        config: KeyProtectionConfig = KeyProtectionConfig()
    ): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Delete existing key if present
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            Log.d(TAG, "Deleted existing key: $alias")
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        var useStrongBox = false

        // Try StrongBox first (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && capabilities.hasStrongBox) {
            builder.setIsStrongBoxBacked(true)
            useStrongBox = true
            Log.d(TAG, "Requesting StrongBox-backed key: $alias")
        } else {
            Log.d(TAG, "Creating TEE-backed key: $alias (StrongBox not available)")
        }

        // User authentication requirements
        if (config.requireUserAuthentication) {
            builder.setUserAuthenticationRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: Fine-grained authentication control
                val authType = if (config.requireBiometric) {
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                } else {
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                }
                builder.setUserAuthenticationParameters(
                    config.authenticationValidityDurationSeconds,
                    authType
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // API 24-29: Duration-based authentication
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(
                    config.authenticationValidityDurationSeconds
                )
            }

            // Invalidate key if biometrics change (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(config.invalidatedByBiometricEnrollment)
            }
        }

        // Require device to be unlocked (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && config.requireUnlockedDevice) {
            builder.setUnlockedDeviceRequired(true)
        }

        return try {
            keyGenerator.init(builder.build())
            val key = keyGenerator.generateKey()
            Log.i(TAG, "Created key '$alias' with security level: ${getKeySecurityLevel(alias)}")
            key
        } catch (e: Exception) {
            // StrongBox may fail on some devices - fallback to TEE
            if (useStrongBox) {
                Log.w(TAG, "StrongBox key creation failed for '$alias', falling back to TEE", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(false)
                }
                keyGenerator.init(builder.build())
                val key = keyGenerator.generateKey()
                Log.i(TAG, "Created key '$alias' with TEE fallback, security level: ${getKeySecurityLevel(alias)}")
                key
            } else {
                throw e
            }
        }
    }

    /**
     * Create a biometric-bound key that requires biometric authentication for each use.
     * The key is invalidated if new biometrics are enrolled.
     */
    fun createBiometricBoundKey(alias: String): SecretKey {
        return createHardwareBackedKey(
            alias = alias,
            config = KeyProtectionConfig(
                requireUserAuthentication = true,
                authenticationValidityDurationSeconds = 0, // Per-operation authentication
                requireBiometric = true,
                invalidatedByBiometricEnrollment = true,
                requireUnlockedDevice = true
            )
        )
    }

    /**
     * Get or create a key. If the key exists, return it; otherwise create a new one.
     * Does NOT migrate existing keys - use migrateToHardwareBacked for that.
     */
    fun getOrCreateKey(
        alias: String,
        config: KeyProtectionConfig = KeyProtectionConfig()
    ): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(alias)) {
            val existingKey = keyStore.getKey(alias, null) as? SecretKey
            if (existingKey != null) {
                Log.d(TAG, "Using existing key: $alias (security level: ${getKeySecurityLevel(alias)})")
                return existingKey
            }
        }

        return createHardwareBackedKey(alias, config)
    }

    /**
     * Check if a key exists in the keystore.
     */
    fun keyExists(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.containsAlias(alias)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking key existence for alias: $alias", e)
            false
        }
    }

    /**
     * Delete a key from the keystore.
     */
    fun deleteKey(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
                Log.i(TAG, "Deleted key: $alias")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting key: $alias", e)
            false
        }
    }

    /**
     * Get a human-readable description of the current security level.
     */
    fun getSecurityLevelDescription(): String {
        return when (capabilities.maxSecurityLevel) {
            SecurityLevel.STRONGBOX -> "StrongBox (Hardware TPM)"
            SecurityLevel.TEE -> "TEE (Trusted Execution Environment)"
            SecurityLevel.SOFTWARE_ONLY -> "Software-only"
        }
    }
}

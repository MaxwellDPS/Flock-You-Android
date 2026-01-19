package com.flockyou.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.flockyou.data.LockMethod
import com.flockyou.data.SecuritySettings
import com.flockyou.data.SecuritySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app lock functionality with hardware-backed security.
 *
 * Security features:
 * - PBKDF2-HMAC-SHA256 key derivation (120,000 iterations) instead of plain SHA-256
 * - Hardware-backed key storage via StrongBox/TEE when available
 * - Fail-closed behavior - throws exception instead of falling back to insecure storage
 * - Constant-time PIN comparison to prevent timing attacks
 * - Brute-force protection with lockout
 * - Duress PIN integration for emergency wipe
 * - Failed auth tracking for nuke triggers
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securitySettingsRepository: SecuritySettingsRepository,
    private val secureKeyManager: SecureKeyManager,
    private val duressAuthenticator: DuressAuthenticator,
    private val failedAuthWatcher: FailedAuthWatcher
) {
    /**
     * Configurable lockout parameters.
     * These can be adjusted based on security requirements.
     */
    data class LockoutConfig(
        val maxFailedAttempts: Int = DEFAULT_MAX_FAILED_ATTEMPTS,
        val lockoutDurationMs: Long = DEFAULT_LOCKOUT_DURATION_MS,
        val escalatingLockout: Boolean = true, // Double lockout time on each consecutive lockout
        val maxLockoutDurationMs: Long = DEFAULT_MAX_LOCKOUT_DURATION_MS
    )

    companion object {
        private const val TAG = "AppLockManager"
        private const val PREFS_NAME = "app_lock_secure_prefs_v2"
        private const val LEGACY_PREFS_NAME = "app_lock_secure_prefs"
        private const val KEY_PIN_HASH = "pin_hash_v2"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_LOCKOUT_COUNT = "lockout_count"
        private const val KEY_SECURITY_LEVEL = "security_level"
        private const val KEY_BIOMETRIC_KEY_ALIAS = "flockyou_biometric_key"

        // Default lockout parameters
        const val DEFAULT_MAX_FAILED_ATTEMPTS = 5
        const val DEFAULT_LOCKOUT_DURATION_MS = 30_000L // 30 seconds
        const val DEFAULT_MAX_LOCKOUT_DURATION_MS = 3600_000L // 1 hour max lockout

        // PBKDF2 parameters (OWASP recommendations)
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val SALT_LENGTH = 32
    }

    // Configurable lockout settings - can be updated at runtime
    private var _lockoutConfig = LockoutConfig()
    var lockoutConfig: LockoutConfig
        get() = _lockoutConfig
        set(value) {
            _lockoutConfig = value
            Log.d(TAG, "Lockout config updated: maxAttempts=${value.maxFailedAttempts}, duration=${value.lockoutDurationMs}ms")
        }

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _lastUnlockTime = MutableStateFlow(0L)
    val lastUnlockTime: StateFlow<Long> = _lastUnlockTime.asStateFlow()

    private var backgroundTime: Long = 0L

    /**
     * Secure storage that FAILS CLOSED - no fallback to unencrypted storage.
     * If encryption fails, a SecurityException is thrown.
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        createSecurePreferences()
    }

    private fun createSecurePreferences(): SharedPreferences {
        return try {
            val masterKeyBuilder = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)

            // Request StrongBox if available (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                secureKeyManager.capabilities.hasStrongBox
            ) {
                masterKeyBuilder.setRequestStrongBoxBacked(true)
                Log.d(TAG, "Requesting StrongBox-backed MasterKey")
            }

            val masterKey = masterKeyBuilder.build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also {
                // Store security level for audit
                val securityLevel = secureKeyManager.getSecurityLevelDescription()
                it.edit().putString(KEY_SECURITY_LEVEL, securityLevel).apply()
                Log.i(TAG, "Created encrypted preferences with security level: $securityLevel")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to create encrypted preferences", e)
            // FAIL CLOSED - throw exception instead of falling back to insecure storage
            throw SecurityException(
                "Cannot create secure storage. App lock functionality is unavailable. " +
                        "This may occur after a device factory reset or security update. " +
                        "Try clearing app data and setting up app lock again.",
                e
            )
        }
    }

    val settings: Flow<SecuritySettings> = securitySettingsRepository.settings

    // Settings update methods
    suspend fun setAppLockEnabled(enabled: Boolean) {
        securitySettingsRepository.setAppLockEnabled(enabled)
    }

    suspend fun setLockMethod(method: LockMethod) {
        securitySettingsRepository.setLockMethod(method)
    }

    suspend fun setLockOnBackground(enabled: Boolean) {
        securitySettingsRepository.setLockOnBackground(enabled)
    }

    suspend fun setLockTimeoutSeconds(seconds: Int) {
        securitySettingsRepository.setLockTimeoutSeconds(seconds)
    }

    /**
     * Check if a PIN has been set.
     */
    fun isPinSet(): Boolean {
        return try {
            encryptedPrefs.getString(KEY_PIN_HASH, null) != null
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception checking PIN status", e)
            false
        }
    }

    /**
     * Validate PIN complexity to reject common/weak sequences.
     * Rejects:
     * - All same digits (1111, 0000)
     * - Sequential ascending (1234, 2345)
     * - Sequential descending (4321, 9876)
     * - Common PINs (0000, 1234, 1111, etc.)
     *
     * @param pin The PIN to validate
     * @return null if valid, or an error message describing the issue
     */
    fun validatePinComplexity(pin: String): String? {
        if (pin.length < 4 || pin.length > 8) {
            return "PIN must be 4-8 digits"
        }

        if (!pin.all { it.isDigit() }) {
            return "PIN must contain only digits"
        }

        // Check for all same digits
        if (pin.all { it == pin[0] }) {
            return "PIN cannot be all the same digit"
        }

        // Check for ascending sequence
        var isAscending = true
        for (i in 1 until pin.length) {
            if (pin[i].code != pin[i - 1].code + 1) {
                isAscending = false
                break
            }
        }
        if (isAscending) {
            return "PIN cannot be a sequential sequence"
        }

        // Check for descending sequence
        var isDescending = true
        for (i in 1 until pin.length) {
            if (pin[i].code != pin[i - 1].code - 1) {
                isDescending = false
                break
            }
        }
        if (isDescending) {
            return "PIN cannot be a sequential sequence"
        }

        // Common weak PINs to reject
        val commonWeakPins = setOf(
            "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
            "1234", "4321", "1212", "2121", "1122", "2211",
            "0123", "3210", "9876", "6789",
            "1357", "2468", "7531", "8642",
            "0852", "2580", "1470", "7410",
            "1230", "0321", "9870", "0789",
            "2000", "1999", "2001", "2020", "2021", "2022", "2023", "2024", "2025",
            "1000", "2000", "3000", "4000", "5000", "6000", "7000", "8000", "9000",
            "00000000", "11111111", "12345678", "87654321"
        )
        if (pin in commonWeakPins) {
            return "PIN is too common and easy to guess"
        }

        return null // Valid PIN
    }

    /**
     * Set a new PIN with PBKDF2 key derivation.
     *
     * @param pin The PIN to set (4-8 digits)
     * @return true if PIN was set successfully
     */
    fun setPin(pin: String): Boolean {
        val complexityError = validatePinComplexity(pin)
        if (complexityError != null) {
            Log.w(TAG, "PIN rejected: $complexityError")
            return false
        }

        return try {
            // Generate random salt
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)

            // Derive key using PBKDF2
            val hash = deriveKey(pin, salt)

            encryptedPrefs.edit()
                .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .apply()

            // Clear sensitive data from memory
            SecureMemory.clear(hash)
            SecureMemory.clear(salt)

            clearFailedAttempts()
            Log.i(TAG, "PIN set successfully with PBKDF2 ($PBKDF2_ITERATIONS iterations)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set PIN", e)
            false
        }
    }

    /**
     * Remove the current PIN.
     */
    fun removePin() {
        try {
            encryptedPrefs.edit()
                .remove(KEY_PIN_HASH)
                .remove(KEY_PIN_SALT)
                .apply()
            clearFailedAttempts()
            Log.i(TAG, "PIN removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove PIN", e)
        }
    }

    /**
     * Result of PIN verification including duress detection.
     */
    sealed class PinVerificationResult {
        /** PIN is correct, app unlocked */
        object Success : PinVerificationResult()
        /** PIN is incorrect */
        object InvalidPin : PinVerificationResult()
        /** Account is locked out due to failed attempts */
        object LockedOut : PinVerificationResult()
        /** Duress PIN entered - nuke triggered */
        object DuressTriggered : PinVerificationResult()
        /** Error during verification */
        data class Error(val message: String) : PinVerificationResult()
    }

    /**
     * Verify a PIN using constant-time comparison (suspend version).
     * Also checks for duress PIN and integrates with failed auth tracking.
     *
     * RECOMMENDED: Use this suspend function to avoid blocking the UI thread.
     * The PIN verification includes PBKDF2 with 120K iterations which is CPU-intensive.
     *
     * @param pin The PIN to verify
     * @return PinVerificationResult indicating the outcome
     */
    suspend fun verifyPinWithResultAsync(pin: String): PinVerificationResult {
        if (isLockedOut()) {
            Log.w(TAG, "PIN verification blocked - account locked out")
            return PinVerificationResult.LockedOut
        }

        return try {
            val storedHash = encryptedPrefs.getString(KEY_PIN_HASH, null)
            val storedSalt = encryptedPrefs.getString(KEY_PIN_SALT, null)

            // Check for duress PIN first
            val duressResult = duressAuthenticator.checkPin(pin, storedHash, storedSalt)

            when (duressResult) {
                is DuressCheckResult.DuressPin -> {
                    Log.w(TAG, "DURESS PIN ENTERED - triggering emergency wipe")
                    // The DuressAuthenticator handles the nuke trigger
                    return PinVerificationResult.DuressTriggered
                }
                is DuressCheckResult.NormalPin -> {
                    // Duress check confirmed this is the normal PIN
                    clearFailedAttempts()
                    failedAuthWatcher.recordSuccessfulAuth()
                    unlock()
                    Log.d(TAG, "PIN verified successfully (via duress check)")
                    return PinVerificationResult.Success
                }
                else -> {
                    // Not duress, not normal - need to check if it's valid
                }
            }

            // If duress check returned InvalidPin or NotEnabled, verify normally
            if (storedHash == null || storedSalt == null) {
                return PinVerificationResult.InvalidPin
            }

            val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
            val inputHash = deriveKey(pin, salt)
            val expectedHash = Base64.decode(storedHash, Base64.NO_WRAP)

            // Constant-time comparison to prevent timing attacks
            val isValid = MessageDigest.isEqual(inputHash, expectedHash)

            // Clear sensitive data from memory
            SecureMemory.clearAll(inputHash, salt)

            if (isValid) {
                clearFailedAttempts()
                failedAuthWatcher.recordSuccessfulAuth()
                unlock()
                Log.d(TAG, "PIN verified successfully")
                PinVerificationResult.Success
            } else {
                recordFailedAttempt()
                failedAuthWatcher.recordFailedAttempt()
                Log.w(TAG, "PIN verification failed")
                PinVerificationResult.InvalidPin
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying PIN", e)
            recordFailedAttempt()
            failedAuthWatcher.recordFailedAttempt()
            PinVerificationResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Verify a PIN using constant-time comparison (blocking version).
     * Also checks for duress PIN and integrates with failed auth tracking.
     *
     * WARNING: This function uses runBlocking which can cause ANR if called from the UI thread.
     * The PIN verification includes PBKDF2 with 120K iterations which is CPU-intensive.
     * PREFER using verifyPinWithResultAsync() from a coroutine instead.
     *
     * @param pin The PIN to verify
     * @return PinVerificationResult indicating the outcome
     */
    @Deprecated(
        message = "Use verifyPinWithResultAsync() to avoid blocking the UI thread",
        replaceWith = ReplaceWith("verifyPinWithResultAsync(pin)")
    )
    fun verifyPinWithResult(pin: String): PinVerificationResult {
        return kotlinx.coroutines.runBlocking {
            verifyPinWithResultAsync(pin)
        }
    }

    /**
     * Verify a PIN using constant-time comparison (suspend version).
     *
     * @param pin The PIN to verify
     * @return true if PIN is correct
     */
    suspend fun verifyPinAsync(pin: String): Boolean {
        return when (verifyPinWithResultAsync(pin)) {
            is PinVerificationResult.Success -> true
            else -> false
        }
    }

    /**
     * Verify a PIN using constant-time comparison (blocking version).
     *
     * WARNING: This uses runBlocking internally. Prefer verifyPinAsync().
     *
     * @param pin The PIN to verify
     * @return true if PIN is correct
     */
    @Deprecated(
        message = "Use verifyPinAsync() to avoid blocking the UI thread",
        replaceWith = ReplaceWith("verifyPinAsync(pin)")
    )
    @Suppress("DEPRECATION")
    fun verifyPin(pin: String): Boolean {
        return when (verifyPinWithResult(pin)) {
            is PinVerificationResult.Success -> true
            else -> false
        }
    }

    /**
     * Derive a key from a PIN using PBKDF2-HMAC-SHA256.
     */
    private fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            pin.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_LENGTH
        )
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Unlock the app.
     */
    fun unlock() {
        _isLocked.value = false
        _lastUnlockTime.value = System.currentTimeMillis()
        backgroundTime = 0L
    }

    /**
     * Called when biometric authentication succeeds.
     * Records successful auth and unlocks the app.
     */
    fun onBiometricSuccess() {
        clearFailedAttempts()
        failedAuthWatcher.recordSuccessfulAuth()
        unlock()
        Log.d(TAG, "Biometric authentication successful")
    }

    /**
     * Called when biometric authentication fails.
     * Records the failure for nuke trigger tracking.
     */
    fun onBiometricFailure() {
        recordFailedAttempt()
        failedAuthWatcher.recordFailedAttempt()
        Log.w(TAG, "Biometric authentication failed")
    }

    /**
     * Get the remaining attempts before nuke trigger (if enabled).
     * Returns null if the failed auth trigger is disabled.
     */
    suspend fun getRemainingNukeAttempts(): Int? {
        return failedAuthWatcher.getRemainingAttempts()
    }

    /**
     * Lock the app.
     */
    fun lock() {
        _isLocked.value = true
    }

    /**
     * Called when the app goes to background.
     */
    fun onAppBackgrounded() {
        backgroundTime = System.currentTimeMillis()
    }

    /**
     * Called when the app comes to foreground.
     * Checks if the app should be locked based on timeout settings.
     */
    suspend fun onAppForegrounded() {
        val settings = securitySettingsRepository.settings.first()

        if (!settings.appLockEnabled || settings.lockMethod == LockMethod.NONE) {
            _isLocked.value = false
            return
        }

        if (!settings.lockOnBackground) {
            return
        }

        // Check timeout
        val timeoutMs = settings.lockTimeoutSeconds * 1000L
        if (timeoutMs < 0) {
            // Never lock on background (only on app restart)
            return
        }

        val elapsed = System.currentTimeMillis() - backgroundTime
        if (backgroundTime > 0 && elapsed > timeoutMs) {
            lock()
        }
    }

    /**
     * Check if the lock screen should be shown.
     */
    suspend fun shouldShowLockScreen(): Boolean {
        val settings = securitySettingsRepository.settings.first()

        if (!settings.appLockEnabled || settings.lockMethod == LockMethod.NONE) {
            return false
        }

        if (!isPinSet() && settings.lockMethod != LockMethod.BIOMETRIC) {
            return false
        }

        return _isLocked.value
    }

    /**
     * Check if the account is locked out due to too many failed attempts.
     */
    fun isLockedOut(): Boolean {
        return try {
            val lockoutUntil = encryptedPrefs.getLong(KEY_LOCKOUT_UNTIL, 0)
            if (lockoutUntil > System.currentTimeMillis()) {
                true
            } else {
                // Clear lockout if expired
                if (lockoutUntil > 0) {
                    encryptedPrefs.edit().remove(KEY_LOCKOUT_UNTIL).apply()
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking lockout status", e)
            false
        }
    }

    /**
     * Get the remaining lockout time in milliseconds.
     */
    fun getRemainingLockoutTime(): Long {
        return try {
            val lockoutUntil = encryptedPrefs.getLong(KEY_LOCKOUT_UNTIL, 0)
            val remaining = lockoutUntil - System.currentTimeMillis()
            if (remaining > 0) remaining else 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get the number of failed PIN attempts.
     */
    fun getFailedAttempts(): Int {
        return try {
            encryptedPrefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun recordFailedAttempt() {
        try {
            val config = _lockoutConfig
            val attempts = getFailedAttempts() + 1
            encryptedPrefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply()

            if (attempts >= config.maxFailedAttempts) {
                // Calculate lockout duration with optional escalation
                val lockoutCount = encryptedPrefs.getInt(KEY_LOCKOUT_COUNT, 0) + 1
                val lockoutDuration = if (config.escalatingLockout) {
                    // Double the lockout duration for each consecutive lockout, up to max
                    minOf(
                        config.lockoutDurationMs * (1L shl (lockoutCount - 1).coerceAtMost(10)),
                        config.maxLockoutDurationMs
                    )
                } else {
                    config.lockoutDurationMs
                }

                val lockoutUntil = System.currentTimeMillis() + lockoutDuration
                encryptedPrefs.edit()
                    .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                    .putInt(KEY_LOCKOUT_COUNT, lockoutCount)
                    .apply()
                Log.w(TAG, "Account locked out for ${lockoutDuration / 1000} seconds after $attempts failed attempts (lockout #$lockoutCount)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording failed attempt", e)
        }
    }

    private fun clearFailedAttempts() {
        try {
            encryptedPrefs.edit()
                .remove(KEY_FAILED_ATTEMPTS)
                .remove(KEY_LOCKOUT_UNTIL)
                .remove(KEY_LOCKOUT_COUNT)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing failed attempts", e)
        }
    }

    /**
     * Get the number of remaining attempts before lockout.
     */
    fun getRemainingAttempts(): Int {
        val failed = getFailedAttempts()
        return (_lockoutConfig.maxFailedAttempts - failed).coerceAtLeast(0)
    }

    /**
     * Create a biometric-bound key for enhanced security.
     *
     * @return true if the key was created successfully
     */
    fun setupBiometricKey(): Boolean {
        return try {
            secureKeyManager.createBiometricBoundKey(KEY_BIOMETRIC_KEY_ALIAS)
            Log.i(TAG, "Biometric-bound key created")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create biometric-bound key", e)
            false
        }
    }

    /**
     * Check if biometric authentication is available and set up.
     */
    fun isBiometricKeySetup(): Boolean {
        return secureKeyManager.keyExists(KEY_BIOMETRIC_KEY_ALIAS)
    }

    /**
     * Get security audit information for display to the user.
     */
    fun getSecurityInfo(): SecurityInfo {
        return SecurityInfo(
            pinProtectionLevel = secureKeyManager.getSecurityLevelDescription(),
            keyDerivationFunction = "PBKDF2-HMAC-SHA256 ($PBKDF2_ITERATIONS iterations)",
            isHardwareBacked = secureKeyManager.capabilities.hasTEE,
            hasStrongBox = secureKeyManager.capabilities.hasStrongBox
        )
    }

    /**
     * Security information for audit/display purposes.
     */
    data class SecurityInfo(
        val pinProtectionLevel: String,
        val keyDerivationFunction: String,
        val isHardwareBacked: Boolean,
        val hasStrongBox: Boolean
    )

    /**
     * Migrate from legacy PIN storage (SHA-256) to PBKDF2.
     * Call this during app upgrade to migrate existing users.
     *
     * @return true if migration was performed or not needed, false if migration failed
     */
    fun migrateLegacyPinIfNeeded(): Boolean {
        try {
            // Check if already migrated (v2 hash exists)
            if (encryptedPrefs.getString(KEY_PIN_HASH, null) != null) {
                return true // Already migrated
            }

            // Try to access legacy prefs
            val legacyPrefs = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    LEGACY_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // No legacy prefs or can't access them
                return true
            }

            val legacyHash = legacyPrefs.getString("pin_hash", null)
            if (legacyHash == null) {
                // No legacy PIN set
                return true
            }

            // Note: We cannot migrate the actual PIN because we only have the SHA-256 hash.
            // The user will need to set up a new PIN.
            // Clear the legacy data and notify the app that re-setup is needed.
            Log.w(TAG, "Legacy PIN detected - user will need to set up a new PIN")

            // Clear legacy data
            legacyPrefs.edit().clear().apply()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during legacy PIN migration", e)
            return false
        }
    }
}

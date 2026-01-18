package com.flockyou.security

import android.content.Context
import android.content.SharedPreferences
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securitySettingsRepository: SecuritySettingsRepository
) {
    companion object {
        private const val TAG = "AppLockManager"
        private const val PREFS_NAME = "app_lock_secure_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
    }

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _lastUnlockTime = MutableStateFlow(0L)
    val lastUnlockTime: StateFlow<Long> = _lastUnlockTime.asStateFlow()

    private var backgroundTime: Long = 0L

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    val settings: Flow<SecuritySettings> = securitySettingsRepository.settings

    fun isPinSet(): Boolean {
        return encryptedPrefs.getString(KEY_PIN_HASH, null) != null
    }

    fun setPin(pin: String): Boolean {
        if (pin.length < 4 || pin.length > 8) {
            return false
        }
        val hash = hashPin(pin)
        encryptedPrefs.edit().putString(KEY_PIN_HASH, hash).apply()
        clearFailedAttempts()
        return true
    }

    fun removePin() {
        encryptedPrefs.edit().remove(KEY_PIN_HASH).apply()
        clearFailedAttempts()
    }

    fun verifyPin(pin: String): Boolean {
        if (isLockedOut()) {
            return false
        }

        val storedHash = encryptedPrefs.getString(KEY_PIN_HASH, null) ?: return false
        val inputHash = hashPin(pin)

        return if (storedHash == inputHash) {
            clearFailedAttempts()
            unlock()
            true
        } else {
            recordFailedAttempt()
            false
        }
    }

    fun unlock() {
        _isLocked.value = false
        _lastUnlockTime.value = System.currentTimeMillis()
        backgroundTime = 0L
    }

    fun lock() {
        _isLocked.value = true
    }

    fun onAppBackgrounded() {
        backgroundTime = System.currentTimeMillis()
    }

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

    fun isLockedOut(): Boolean {
        val lockoutUntil = encryptedPrefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        if (lockoutUntil > System.currentTimeMillis()) {
            return true
        }
        // Clear lockout if expired
        if (lockoutUntil > 0) {
            encryptedPrefs.edit().remove(KEY_LOCKOUT_UNTIL).apply()
        }
        return false
    }

    fun getRemainingLockoutTime(): Long {
        val lockoutUntil = encryptedPrefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun getFailedAttempts(): Int {
        return encryptedPrefs.getInt(KEY_FAILED_ATTEMPTS, 0)
    }

    private fun recordFailedAttempt() {
        val attempts = getFailedAttempts() + 1
        encryptedPrefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply()

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            encryptedPrefs.edit().putLong(KEY_LOCKOUT_UNTIL, lockoutUntil).apply()
        }
    }

    private fun clearFailedAttempts() {
        encryptedPrefs.edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

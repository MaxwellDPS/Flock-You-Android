package com.flockyou.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.flockyou.data.NukeSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a duress PIN authentication check.
 */
sealed class DuressCheckResult {
    /** Normal PIN - proceed with normal unlock */
    object NormalPin : DuressCheckResult()

    /** Duress PIN - trigger nuke and show fake app */
    object DuressPin : DuressCheckResult()

    /** Neither normal nor duress PIN */
    object InvalidPin : DuressCheckResult()

    /** Duress PIN feature is not enabled */
    object NotEnabled : DuressCheckResult()
}

/**
 * Handles duress PIN functionality - a secondary PIN that wipes data instead of unlocking.
 *
 * Use cases:
 * - Under coercion to unlock device
 * - When forced to provide PIN by authorities
 * - Emergency data destruction while appearing to comply
 *
 * The duress PIN:
 * - Uses the same PBKDF2 derivation as the normal PIN
 * - Cannot be the same as the normal PIN
 * - Shows a fake empty app after triggering
 * - Performs the nuke silently in the background
 */
@Singleton
class DuressAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nukeSettingsRepository: NukeSettingsRepository,
    private val nukeManager: NukeManager
) {
    companion object {
        private const val TAG = "DuressAuthenticator"

        // PBKDF2 parameters (matching AppLockManager)
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val SALT_LENGTH = 32
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Check if an entered PIN is the duress PIN.
     * This should be called BEFORE checking the normal PIN.
     *
     * @param enteredPin The PIN entered by the user
     * @param normalPinHash The hash of the normal PIN (to verify it's different)
     * @param normalPinSalt The salt of the normal PIN
     * @return DuressCheckResult indicating the action to take
     */
    suspend fun checkPin(
        enteredPin: String,
        normalPinHash: String?,
        normalPinSalt: String?
    ): DuressCheckResult {
        val settings = nukeSettingsRepository.settings.first()

        // Check if duress PIN is enabled
        if (!settings.nukeEnabled || !settings.duressPinEnabled) {
            return DuressCheckResult.NotEnabled
        }

        // Check if duress PIN is set
        if (settings.duressPinHash.isBlank() || settings.duressPinSalt.isBlank()) {
            Log.d(TAG, "Duress PIN not set")
            return DuressCheckResult.NotEnabled
        }

        // Check if entered PIN matches the duress PIN
        val duressSalt = Base64.decode(settings.duressPinSalt, Base64.NO_WRAP)
        val enteredHash = deriveKey(enteredPin, duressSalt)
        val expectedHash = Base64.decode(settings.duressPinHash, Base64.NO_WRAP)

        // Constant-time comparison to prevent timing attacks
        val isDuressPin = MessageDigest.isEqual(enteredHash, expectedHash)

        // Clear sensitive data from memory
        SecureMemory.clear(enteredHash)
        SecureMemory.clear(duressSalt)

        if (isDuressPin) {
            Log.w(TAG, "DURESS PIN DETECTED - Triggering nuke")
            triggerDuressNuke(settings.duressPinShowFakeApp)
            return DuressCheckResult.DuressPin
        }

        // Check if it's the normal PIN (to return correct result)
        if (normalPinHash != null && normalPinSalt != null) {
            val normalSalt = Base64.decode(normalPinSalt, Base64.NO_WRAP)
            val normalEnteredHash = deriveKey(enteredPin, normalSalt)
            val normalExpectedHash = Base64.decode(normalPinHash, Base64.NO_WRAP)

            val isNormalPin = MessageDigest.isEqual(normalEnteredHash, normalExpectedHash)

            SecureMemory.clear(normalEnteredHash)
            SecureMemory.clear(normalSalt)

            if (isNormalPin) {
                return DuressCheckResult.NormalPin
            }
        }

        return DuressCheckResult.InvalidPin
    }

    /**
     * Set a new duress PIN.
     *
     * @param pin The duress PIN to set (4-8 digits)
     * @param normalPinHash The hash of the normal PIN (to ensure they're different)
     * @param normalPinSalt The salt of the normal PIN
     * @return true if duress PIN was set successfully
     */
    suspend fun setDuressPin(
        pin: String,
        normalPinHash: String?,
        normalPinSalt: String?
    ): Boolean {
        if (pin.length < 4 || pin.length > 8) {
            Log.w(TAG, "Duress PIN must be 4-8 digits")
            return false
        }

        // Verify the duress PIN is different from the normal PIN
        if (normalPinHash != null && normalPinSalt != null) {
            val normalSalt = Base64.decode(normalPinSalt, Base64.NO_WRAP)
            val pinHash = deriveKey(pin, normalSalt)
            val normalExpectedHash = Base64.decode(normalPinHash, Base64.NO_WRAP)

            val isSameAsNormal = MessageDigest.isEqual(pinHash, normalExpectedHash)

            SecureMemory.clear(pinHash)
            SecureMemory.clear(normalSalt)

            if (isSameAsNormal) {
                Log.w(TAG, "Duress PIN cannot be the same as normal PIN")
                return false
            }
        }

        return try {
            // Generate random salt
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)

            // Derive key using PBKDF2
            val hash = deriveKey(pin, salt)

            // Store the hash and salt
            nukeSettingsRepository.setDuressPinHash(
                Base64.encodeToString(hash, Base64.NO_WRAP),
                Base64.encodeToString(salt, Base64.NO_WRAP)
            )

            // Clear sensitive data from memory
            SecureMemory.clear(hash)
            SecureMemory.clear(salt)

            Log.i(TAG, "Duress PIN set successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set duress PIN", e)
            false
        }
    }

    /**
     * Remove the duress PIN.
     */
    suspend fun removeDuressPin() {
        nukeSettingsRepository.clearDuressPin()
        Log.i(TAG, "Duress PIN removed")
    }

    /**
     * Check if a duress PIN has been set.
     */
    suspend fun isDuressPinSet(): Boolean {
        val settings = nukeSettingsRepository.settings.first()
        return settings.duressPinHash.isNotBlank() && settings.duressPinSalt.isNotBlank()
    }

    /**
     * Check if duress PIN feature is enabled.
     */
    suspend fun isDuressPinEnabled(): Boolean {
        val settings = nukeSettingsRepository.settings.first()
        return settings.nukeEnabled && settings.duressPinEnabled
    }

    /**
     * Derive a key from a PIN using PBKDF2-HMAC-SHA256.
     * Uses the same parameters as AppLockManager for consistency.
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

    private fun triggerDuressNuke(showFakeApp: Boolean) {
        scope.launch {
            // Execute the nuke
            val result = nukeManager.executeNuke(NukeTriggerSource.DURESS_PIN)

            if (result.success) {
                Log.w(TAG, "Duress nuke completed - showFakeApp: $showFakeApp")
            } else {
                Log.e(TAG, "Duress nuke failed: ${result.errorMessage}")
            }
        }
    }
}

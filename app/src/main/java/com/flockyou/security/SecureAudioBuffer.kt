package com.flockyou.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure audio buffer that encrypts audio samples in memory.
 *
 * SECURITY FEATURES:
 * 1. All audio samples are encrypted using AES-256-GCM before being stored in memory
 * 2. A unique encryption key is generated per buffer instance (not persisted)
 * 3. Audio data is immediately encrypted when written and decrypted only during analysis
 * 4. Clear operations cryptographically wipe all data
 * 5. Keys are stored in Android Keystore (hardware-backed when available)
 *
 * PRIVACY NOTE:
 * This buffer is designed for ultrasonic beacon detection where audio is analyzed
 * for frequency content only. Raw audio data is never stored to disk.
 */
class SecureAudioBuffer(
    private val capacity: Int,
    private val sampleRate: Int = 44100
) : AutoCloseable {

    companion object {
        private const val TAG = "SecureAudioBuffer"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "secure_audio_buffer_"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    // Unique key alias for this buffer instance
    private val keyAlias = KEY_ALIAS_PREFIX + UUID.randomUUID().toString()

    // Encrypted storage
    private var encryptedData: ByteArray? = null
    private var encryptionIv: ByteArray? = null
    private var sampleCount: Int = 0

    // State tracking
    private var isDestroyed = false

    init {
        // Generate a unique encryption key for this buffer
        generateKey()
        Log.d(TAG, "SecureAudioBuffer initialized with capacity: $capacity samples")
    }

    /**
     * Generate a hardware-backed AES key for this buffer instance.
     * Key is ephemeral and will be deleted when buffer is destroyed.
     */
    private fun generateKey() {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val keySpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Key should only be valid while device is unlocked
                .setUserAuthenticationRequired(false) // Don't require auth for real-time processing
                .build()

            keyGenerator.init(keySpec)
            keyGenerator.generateKey()

            Log.d(TAG, "Generated secure encryption key: $keyAlias")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate encryption key", e)
            throw SecurityException("Cannot create secure audio buffer: key generation failed", e)
        }
    }

    /**
     * Get the secret key from Android Keystore.
     */
    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    /**
     * Write audio samples to the secure buffer.
     * Samples are immediately encrypted in memory.
     *
     * @param samples Audio samples as ShortArray (16-bit PCM)
     * @param count Number of samples to write (defaults to array length)
     */
    @Synchronized
    fun write(samples: ShortArray, count: Int = samples.size) {
        checkNotDestroyed()

        val samplesToWrite = minOf(count, samples.size, capacity)

        // Convert shorts to bytes
        val byteBuffer = ByteBuffer.allocate(samplesToWrite * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesToWrite) {
            byteBuffer.putShort(samples[i])
        }
        val plainData = byteBuffer.array()

        try {
            // Encrypt the audio data
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())

            // Store IV for decryption
            encryptionIv = cipher.iv.copyOf()

            // Encrypt and store
            encryptedData = cipher.doFinal(plainData)
            sampleCount = samplesToWrite

        } finally {
            // Securely clear the plaintext data
            SecureMemory.clear(plainData)
            SecureMemory.clear(byteBuffer.array())
        }
    }

    /**
     * Read and decrypt audio samples for analysis.
     * Returns a copy of the decrypted samples.
     *
     * IMPORTANT: The caller is responsible for clearing the returned array
     * after use by calling SecureMemory.clear(samples).
     *
     * @return Decrypted audio samples, or empty array if buffer is empty
     */
    @Synchronized
    fun read(): ShortArray {
        checkNotDestroyed()

        val encrypted = encryptedData ?: return ShortArray(0)
        val iv = encryptionIv ?: return ShortArray(0)

        var decrypted: ByteArray? = null
        try {
            // Decrypt the audio data
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), gcmSpec)

            decrypted = cipher.doFinal(encrypted)

            // Convert bytes back to shorts
            val byteBuffer = ByteBuffer.wrap(decrypted)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

            val samples = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                samples[i] = byteBuffer.getShort()
            }

            return samples

        } finally {
            // Clear decrypted data from memory
            decrypted?.let { SecureMemory.clear(it) }
        }
    }

    /**
     * Perform secure analysis on the audio data without exposing raw samples.
     * This is the preferred method for frequency analysis.
     *
     * @param analyzer Function that receives the samples for analysis
     * @return Result from the analyzer function
     */
    @Synchronized
    fun <T> analyze(analyzer: (ShortArray) -> T): T {
        checkNotDestroyed()

        val samples = read()
        return try {
            analyzer(samples)
        } finally {
            // Securely clear samples after analysis
            SecureMemory.clear(samples)
        }
    }

    /**
     * Clear the buffer and cryptographically wipe all data.
     */
    @Synchronized
    fun clear() {
        if (isDestroyed) return

        // Overwrite encrypted data with random bytes then zeros
        encryptedData?.let { data ->
            // First pass: random overwrite
            java.security.SecureRandom().nextBytes(data)
            // Second pass: zero overwrite
            Arrays.fill(data, 0.toByte())
        }

        encryptionIv?.let { iv ->
            Arrays.fill(iv, 0.toByte())
        }

        encryptedData = null
        encryptionIv = null
        sampleCount = 0

        Log.d(TAG, "Buffer cleared securely")
    }

    /**
     * Get the number of samples currently in the buffer.
     */
    @Synchronized
    fun getSampleCount(): Int {
        checkNotDestroyed()
        return sampleCount
    }

    /**
     * Check if buffer is empty.
     */
    @Synchronized
    fun isEmpty(): Boolean {
        checkNotDestroyed()
        return sampleCount == 0
    }

    /**
     * Destroy the buffer and delete the encryption key.
     * After this call, the buffer cannot be used.
     */
    override fun close() {
        destroy()
    }

    /**
     * Destroy the buffer and delete the encryption key.
     */
    @Synchronized
    fun destroy() {
        if (isDestroyed) return

        // Clear all data
        clear()

        // Delete the encryption key from keystore
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
                Log.d(TAG, "Deleted encryption key: $keyAlias")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete encryption key", e)
        }

        isDestroyed = true
        Log.d(TAG, "SecureAudioBuffer destroyed")
    }

    /**
     * Check if buffer has been destroyed.
     */
    fun isDestroyed(): Boolean = isDestroyed

    private fun checkNotDestroyed() {
        if (isDestroyed) {
            throw IllegalStateException("SecureAudioBuffer has been destroyed")
        }
    }

    /**
     * Finalize - ensure cleanup if not explicitly destroyed.
     */
    protected fun finalize() {
        if (!isDestroyed) {
            Log.w(TAG, "SecureAudioBuffer was not properly destroyed - cleaning up")
            destroy()
        }
    }
}

/**
 * Extension function to safely use a SecureAudioBuffer in a scoped manner.
 * Buffer is automatically destroyed after use.
 */
inline fun <T> withSecureAudioBuffer(
    capacity: Int,
    sampleRate: Int = 44100,
    block: (SecureAudioBuffer) -> T
): T {
    val buffer = SecureAudioBuffer(capacity, sampleRate)
    return try {
        block(buffer)
    } finally {
        buffer.destroy()
    }
}

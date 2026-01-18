package com.flockyou.security

import java.util.Arrays

/**
 * Utilities for protecting sensitive data in memory.
 * Provides secure clearing and scoped operations for sensitive data.
 */
object SecureMemory {

    /**
     * Securely clear a byte array from memory by overwriting with zeros.
     */
    fun clear(data: ByteArray?) {
        data?.let {
            Arrays.fill(it, 0.toByte())
        }
    }

    /**
     * Securely clear a char array from memory by overwriting with null characters.
     */
    fun clear(data: CharArray?) {
        data?.let {
            Arrays.fill(it, '\u0000')
        }
    }

    /**
     * Securely clear a short array from memory by overwriting with zeros.
     * Used for audio sample buffers.
     */
    fun clear(data: ShortArray?) {
        data?.let {
            Arrays.fill(it, 0.toShort())
        }
    }

    /**
     * Execute an operation with sensitive byte data, automatically clearing after use.
     *
     * @param data The sensitive data to use
     * @param operation The operation to perform with the data
     * @return The result of the operation
     */
    inline fun <T> withSensitiveData(
        data: ByteArray,
        operation: (ByteArray) -> T
    ): T {
        return try {
            operation(data)
        } finally {
            clear(data)
        }
    }

    /**
     * Execute an operation with sensitive char data, automatically clearing after use.
     *
     * @param data The sensitive data to use
     * @param operation The operation to perform with the data
     * @return The result of the operation
     */
    inline fun <T> withSensitiveChars(
        data: CharArray,
        operation: (CharArray) -> T
    ): T {
        return try {
            operation(data)
        } finally {
            clear(data)
        }
    }

    /**
     * A secure wrapper for strings that automatically clears from memory when closed.
     * Use with try-with-resources or Kotlin's use {} block.
     *
     * Example:
     * ```kotlin
     * SecureString(sensitiveValue).use { secure ->
     *     doSomethingWith(secure.value)
     * } // automatically cleared
     * ```
     */
    class SecureString(value: String) : AutoCloseable {
        private val chars: CharArray = value.toCharArray()
        private var cleared = false

        /**
         * Get the string value. Throws if already cleared.
         */
        val value: String
            get() {
                if (cleared) throw IllegalStateException("SecureString has been cleared")
                return String(chars)
            }

        /**
         * Check if this SecureString has been cleared.
         */
        val isCleared: Boolean
            get() = cleared

        /**
         * Clear the string from memory. Safe to call multiple times.
         */
        override fun close() {
            if (!cleared) {
                clear(chars)
                cleared = true
            }
        }

        /**
         * Execute an operation with the string value, then clear.
         */
        inline fun <T> use(block: (String) -> T): T {
            return try {
                block(value)
            } finally {
                close()
            }
        }
    }

    /**
     * A secure wrapper for byte arrays that automatically clears from memory when closed.
     */
    class SecureBytes(value: ByteArray) : AutoCloseable {
        @PublishedApi
        internal val bytes: ByteArray = value.copyOf()
        private var cleared = false

        /**
         * Get the byte array. Throws if already cleared.
         * Note: Returns a copy to prevent external modification.
         */
        val value: ByteArray
            get() {
                if (cleared) throw IllegalStateException("SecureBytes has been cleared")
                return bytes.copyOf()
            }

        /**
         * Check if this SecureBytes has been cleared.
         */
        val isCleared: Boolean
            get() = cleared

        /**
         * Clear the bytes from memory. Safe to call multiple times.
         */
        override fun close() {
            if (!cleared) {
                clear(bytes)
                cleared = true
            }
        }

        /**
         * Execute an operation with the byte array, then clear.
         */
        inline fun <T> use(block: (ByteArray) -> T): T {
            return try {
                block(bytes)
            } finally {
                close()
            }
        }
    }

    /**
     * Clear multiple byte arrays at once.
     */
    fun clearAll(vararg arrays: ByteArray?) {
        arrays.forEach { clear(it) }
    }

    /**
     * Clear multiple char arrays at once.
     */
    fun clearAllChars(vararg arrays: CharArray?) {
        arrays.forEach { clear(it) }
    }
}

package com.flockyou.data.patterns

import android.content.Context
import android.util.Log
import com.flockyou.BuildConfig
import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.SurveillancePattern
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing detection pattern updates.
 * Provides a mechanism for downloading updated patterns while maintaining
 * built-in defaults that are always available.
 *
 * Pattern sources (in priority order):
 * 1. Downloaded patterns (if newer than built-in)
 * 2. Built-in patterns (compiled into app)
 *
 * Downloaded patterns are stored locally and validated before use.
 */
@Singleton
class PatternUpdateService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PatternUpdateService"
        private const val PATTERNS_FILE = "custom_patterns.json"
        private const val PATTERNS_VERSION_KEY = "patterns_version"

        // Built-in pattern version (increment when DetectionPatterns.kt is updated)
        const val BUILTIN_VERSION = 1
    }

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val patternsDir: File by lazy {
        File(context.filesDir, "patterns").also { it.mkdirs() }
    }

    private val patternsFile: File by lazy {
        File(patternsDir, PATTERNS_FILE)
    }

    // State for UI
    private val _customPatterns = MutableStateFlow<List<SurveillancePattern>>(emptyList())
    val customPatterns: StateFlow<List<SurveillancePattern>> = _customPatterns

    private val _lastUpdateTime = MutableStateFlow<Long?>(null)
    val lastUpdateTime: StateFlow<Long?> = _lastUpdateTime

    private val _patternVersion = MutableStateFlow(BUILTIN_VERSION)
    val patternVersion: StateFlow<Int> = _patternVersion

    init {
        loadCustomPatterns()
    }

    /**
     * Get all active patterns (built-in + custom).
     * Custom patterns override built-in patterns with the same ID.
     */
    fun getAllPatterns(): List<SurveillancePattern> {
        val builtIn = DetectionPatterns.allPatterns
        val custom = _customPatterns.value

        if (custom.isEmpty()) {
            return builtIn
        }

        // Merge: custom patterns override built-in patterns with same ID
        val customIds = custom.map { it.id }.toSet()
        val filtered = builtIn.filter { it.id !in customIds }

        return custom + filtered
    }

    /**
     * Add a custom pattern.
     */
    suspend fun addCustomPattern(pattern: SurveillancePattern) = withContext(Dispatchers.IO) {
        val current = _customPatterns.value.toMutableList()
        current.removeAll { it.id == pattern.id }
        current.add(pattern)
        _customPatterns.value = current
        saveCustomPatterns(current)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Added custom pattern: ${pattern.id}")
        }
    }

    /**
     * Remove a custom pattern.
     */
    suspend fun removeCustomPattern(patternId: String) = withContext(Dispatchers.IO) {
        val current = _customPatterns.value.toMutableList()
        current.removeAll { it.id == patternId }
        _customPatterns.value = current
        saveCustomPatterns(current)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Removed custom pattern: $patternId")
        }
    }

    /**
     * Clear all custom patterns.
     */
    suspend fun clearCustomPatterns() = withContext(Dispatchers.IO) {
        _customPatterns.value = emptyList()
        if (patternsFile.exists()) {
            patternsFile.delete()
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Cleared all custom patterns")
        }
    }

    /**
     * Import patterns from JSON string.
     * Returns the number of patterns imported.
     */
    suspend fun importPatterns(json: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<PatternExport>() {}.type
            val export: PatternExport = gson.fromJson(json, type)

            // Validate patterns
            val validPatterns = export.patterns.filter { validatePattern(it) }

            if (validPatterns.isEmpty()) {
                return@withContext Result.failure(Exception("No valid patterns found"))
            }

            // Merge with existing custom patterns
            val current = _customPatterns.value.toMutableList()
            val importedIds = validPatterns.map { it.id }.toSet()
            current.removeAll { it.id in importedIds }
            current.addAll(validPatterns)

            _customPatterns.value = current
            _patternVersion.value = export.version
            saveCustomPatterns(current)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Imported ${validPatterns.size} patterns (version ${export.version})")
            }

            Result.success(validPatterns.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import patterns", e)
            Result.failure(e)
        }
    }

    /**
     * Export all custom patterns to JSON.
     */
    suspend fun exportPatterns(): String = withContext(Dispatchers.IO) {
        val export = PatternExport(
            version = _patternVersion.value,
            exportedAt = System.currentTimeMillis(),
            patterns = _customPatterns.value
        )
        gson.toJson(export)
    }

    /**
     * Validate a pattern before importing.
     */
    private fun validatePattern(pattern: SurveillancePattern): Boolean {
        // Basic validation
        if (pattern.id.isBlank()) return false
        if (pattern.name.isBlank()) return false

        // At least one matching criteria must be present
        val hasMatchCriteria = !pattern.ssidPatterns.isNullOrEmpty() ||
                !pattern.bleNamePatterns.isNullOrEmpty() ||
                !pattern.macPrefixes.isNullOrEmpty() ||
                !pattern.serviceUuids.isNullOrEmpty()

        if (!hasMatchCriteria) return false

        // Validate regex patterns
        pattern.ssidPatterns?.forEach { regex ->
            try {
                Regex(regex)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Invalid SSID regex: $regex")
                }
                return false
            }
        }

        pattern.bleNamePatterns?.forEach { regex ->
            try {
                Regex(regex)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Invalid BLE name regex: $regex")
                }
                return false
            }
        }

        return true
    }

    private fun loadCustomPatterns() {
        try {
            if (patternsFile.exists()) {
                val json = patternsFile.readText()
                val type = object : TypeToken<PatternExport>() {}.type
                val export: PatternExport = gson.fromJson(json, type)

                _customPatterns.value = export.patterns.filter { validatePattern(it) }
                _patternVersion.value = export.version
                _lastUpdateTime.value = export.exportedAt

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded ${_customPatterns.value.size} custom patterns")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom patterns", e)
            _customPatterns.value = emptyList()
        }
    }

    private fun saveCustomPatterns(patterns: List<SurveillancePattern>) {
        try {
            val export = PatternExport(
                version = _patternVersion.value,
                exportedAt = System.currentTimeMillis(),
                patterns = patterns
            )
            patternsFile.writeText(gson.toJson(export))
            _lastUpdateTime.value = export.exportedAt
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom patterns", e)
        }
    }

    /**
     * Data class for pattern import/export.
     */
    data class PatternExport(
        val version: Int,
        val exportedAt: Long,
        val patterns: List<SurveillancePattern>
    )
}

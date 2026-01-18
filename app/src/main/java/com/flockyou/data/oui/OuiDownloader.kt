package com.flockyou.data.oui

import android.content.Context
import android.util.Log
import com.flockyou.data.model.OuiEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class OuiDownloadResult {
    data class Success(val entries: List<OuiEntry>, val totalParsed: Int) : OuiDownloadResult()
    data class Error(val message: String, val exception: Exception? = null) : OuiDownloadResult()
}

@Singleton
class OuiDownloader @Inject constructor(
    @Suppress("UNUSED_PARAMETER")
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OuiDownloader"
        const val IEEE_OUI_CSV_URL = "https://standards-oui.ieee.org/oui/oui.csv"
        private const val DOWNLOAD_TIMEOUT_SECONDS = 120L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Download and parse IEEE OUI CSV file.
     * Uses streaming to handle the ~3MB file efficiently.
     */
    suspend fun downloadAndParse(): OuiDownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting OUI download from $IEEE_OUI_CSV_URL")

            val request = Request.Builder()
                .url(IEEE_OUI_CSV_URL)
                .header("Accept", "text/csv")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext OuiDownloadResult.Error(
                    "HTTP ${response.code}: ${response.message}"
                )
            }

            val body = response.body ?: return@withContext OuiDownloadResult.Error(
                "Empty response body"
            )

            body.byteStream().use { inputStream ->
                parseOuiCsv(inputStream)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error downloading OUI data", e)
            OuiDownloadResult.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error downloading OUI data", e)
            OuiDownloadResult.Error("Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Parse IEEE OUI CSV format.
     * Format: Registry,Assignment,Organization Name,Address
     * Example: MA-L,D83ADD,Dell Inc.,1 Dell Way Round Rock TX US 78682
     */
    private fun parseOuiCsv(inputStream: InputStream): OuiDownloadResult {
        val entries = mutableListOf<OuiEntry>()
        var lineNumber = 0
        var errorCount = 0
        val currentTime = System.currentTimeMillis()

        try {
            inputStream.bufferedReader().use { reader ->
                // Skip header line
                reader.readLine()
                lineNumber++

                reader.forEachLine { line ->
                    lineNumber++
                    try {
                        parseCsvLine(line, currentTime)?.let { entry ->
                            entries.add(entry)
                        }
                    } catch (e: Exception) {
                        errorCount++
                        // Log but continue - some malformed lines are expected
                        if (errorCount < 10) {
                            Log.w(TAG, "Parse error line $lineNumber: ${e.message}")
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                return OuiDownloadResult.Error("No valid OUI entries found in CSV")
            }

            Log.d(TAG, "Parsed ${entries.size} OUI entries from $lineNumber lines ($errorCount errors)")
            return OuiDownloadResult.Success(entries, lineNumber - 1)
        } catch (e: Exception) {
            return OuiDownloadResult.Error("Parse error at line $lineNumber: ${e.message}", e)
        }
    }

    /**
     * Parse a single CSV line.
     * Handles quoted fields with embedded commas.
     */
    private fun parseCsvLine(line: String, timestamp: Long): OuiEntry? {
        if (line.isBlank()) return null

        val fields = parseCsvFields(line)
        if (fields.size < 3) return null

        val registry = fields[0].trim()
        val assignment = fields[1].trim()
        val organization = fields[2].trim()
        val address = if (fields.size > 3) fields[3].trim() else null

        // Validate assignment is 6 hex characters
        if (!assignment.matches(Regex("^[0-9A-Fa-f]{6}$"))) {
            return null
        }

        // Convert assignment to colon-separated format
        val ouiPrefix = "${assignment.substring(0, 2)}:${assignment.substring(2, 4)}:${assignment.substring(4, 6)}"
            .uppercase()

        return OuiEntry(
            ouiPrefix = ouiPrefix,
            organizationName = organization,
            registry = registry,
            address = if (address.isNullOrBlank()) null else address,
            lastUpdated = timestamp
        )
    }

    /**
     * Parse CSV fields handling quoted values
     */
    private fun parseCsvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        fields.add(current.toString())

        return fields
    }
}

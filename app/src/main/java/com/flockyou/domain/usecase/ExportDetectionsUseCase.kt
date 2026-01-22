package com.flockyou.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.flockyou.R
import com.flockyou.data.model.Detection
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.security.SecureKeyManager
import com.flockyou.security.SecureMemory
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Use case for exporting detection data in various formats.
 *
 * Security features:
 * - Optional hardware-backed encryption for exported files
 * - Encrypted exports use AES-GCM with hardware-backed keys (StrongBox/TEE)
 * - Automatic cleanup of old export files (24-hour retention)
 */
class ExportDetectionsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DetectionRepository,
    private val secureKeyManager: SecureKeyManager
) {
    companion object {
        private const val TAG = "ExportDetectionsUseCase"
        private const val EXPORT_KEY_ALIAS = "flockyou_export_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val csvDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Export detections to CSV format
     *
     * @param encrypted If true, the file will be encrypted with a hardware-backed key
     */
    suspend fun exportToCsv(encrypted: Boolean = false): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val detections = repository.allDetections.first()
            val csv = buildCsv(detections)
            val file = if (encrypted) {
                saveEncryptedFile(csv.toByteArray(Charsets.UTF_8), "detections_${dateFormat.format(Date())}.csv.enc")
            } else {
                saveToFile(csv, "detections_${dateFormat.format(Date())}.csv")
            }
            val uri = getShareableUri(file)
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CSV", e)
            Result.failure(e)
        }
    }

    /**
     * Export detections to JSON format
     *
     * @param encrypted If true, the file will be encrypted with a hardware-backed key
     */
    suspend fun exportToJson(encrypted: Boolean = false): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val detections = repository.allDetections.first()
            val json = gson.toJson(ExportData(
                exportedAt = System.currentTimeMillis(),
                version = "1.0",
                detectionCount = detections.size,
                detections = detections.map { it.toExportDetection() }
            ))
            val file = if (encrypted) {
                saveEncryptedFile(json.toByteArray(Charsets.UTF_8), "detections_${dateFormat.format(Date())}.json.enc")
            } else {
                saveToFile(json, "detections_${dateFormat.format(Date())}.json")
            }
            val uri = getShareableUri(file)
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export JSON", e)
            Result.failure(e)
        }
    }

    /**
     * Export detections to KML format for Google Earth
     *
     * @param encrypted If true, the file will be encrypted with a hardware-backed key
     */
    suspend fun exportToKml(encrypted: Boolean = false): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val detections = repository.detectionsWithLocation.first()
            val kml = buildKml(detections)
            val file = if (encrypted) {
                saveEncryptedFile(kml.toByteArray(Charsets.UTF_8), "detections_${dateFormat.format(Date())}.kml.enc")
            } else {
                saveToFile(kml, "detections_${dateFormat.format(Date())}.kml")
            }
            val uri = getShareableUri(file)
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export KML", e)
            Result.failure(e)
        }
    }

    /**
     * Decrypt an encrypted export file
     *
     * @param encryptedFile The encrypted file to decrypt
     * @return The decrypted content as a ByteArray
     */
    suspend fun decryptExportFile(encryptedFile: File): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val encryptedData = encryptedFile.readBytes()
            val decrypted = decryptContent(encryptedData)
            Result.success(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt export file", e)
            Result.failure(e)
        }
    }

    /**
     * Create a share intent for the exported file
     */
    fun createShareIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Get security information about export encryption
     */
    fun getExportSecurityInfo(): ExportSecurityInfo {
        val hasKey = secureKeyManager.keyExists(EXPORT_KEY_ALIAS)
        val securityLevel = if (hasKey) {
            secureKeyManager.getKeySecurityLevel(EXPORT_KEY_ALIAS)
        } else {
            secureKeyManager.capabilities.maxSecurityLevel
        }

        return ExportSecurityInfo(
            encryptionAvailable = true,
            keyExists = hasKey,
            securityLevel = when (securityLevel) {
                SecureKeyManager.SecurityLevel.STRONGBOX -> "StrongBox (Hardware TPM)"
                SecureKeyManager.SecurityLevel.TEE -> "TEE (Hardware-backed)"
                SecureKeyManager.SecurityLevel.SOFTWARE_ONLY -> "Software-only"
            },
            isHardwareBacked = securityLevel != SecureKeyManager.SecurityLevel.SOFTWARE_ONLY
        )
    }

    data class ExportSecurityInfo(
        val encryptionAvailable: Boolean,
        val keyExists: Boolean,
        val securityLevel: String,
        val isHardwareBacked: Boolean
    )

    private fun buildCsv(detections: List<Detection>): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("ID,Timestamp,Device Type,Threat Level,Protocol,Detection Method," +
                "RSSI,Signal Strength,MAC Address,SSID,Device Name,Manufacturer," +
                "Latitude,Longitude,Seen Count,Is Active,Last Seen")

        // Data rows
        detections.forEach { d ->
            sb.appendLine(listOf(
                d.id,
                csvDateFormat.format(Date(d.timestamp)),
                d.deviceType.name,
                d.threatLevel.name,
                d.protocol.name,
                d.detectionMethod.name,
                d.rssi,
                d.signalStrength.name,
                d.macAddress ?: "",
                escapeCsv(d.ssid ?: ""),
                escapeCsv(d.deviceName ?: ""),
                escapeCsv(d.manufacturer ?: ""),
                d.latitude?.toString() ?: "",
                d.longitude?.toString() ?: "",
                d.seenCount,
                d.isActive,
                csvDateFormat.format(Date(d.lastSeenTimestamp))
            ).joinToString(","))
        }

        return sb.toString()
    }

    /**
     * Escape value for CSV format.
     * Always quote the value to ensure consistent parsing across different CSV readers.
     */
    private fun escapeCsv(value: String): String {
        // Always quote values for consistent CSV parsing
        // Escape any existing quotes by doubling them
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun buildKml(detections: List<Detection>): String {
        val sb = StringBuilder()

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("""  <Document>""")
        sb.appendLine("""    <name>${context.getString(R.string.kml_export_name)}</name>""")
        sb.appendLine("""    <description>Exported surveillance device detections</description>""")

        // Define styles for different threat levels
        sb.appendLine("""    <Style id="critical"><IconStyle><color>ff0000ff</color><scale>1.5</scale></IconStyle></Style>""")
        sb.appendLine("""    <Style id="high"><IconStyle><color>ff0080ff</color><scale>1.3</scale></IconStyle></Style>""")
        sb.appendLine("""    <Style id="medium"><IconStyle><color>ff00ffff</color><scale>1.1</scale></IconStyle></Style>""")
        sb.appendLine("""    <Style id="low"><IconStyle><color>ff00ff00</color><scale>1.0</scale></IconStyle></Style>""")
        sb.appendLine("""    <Style id="info"><IconStyle><color>ffff0000</color><scale>0.9</scale></IconStyle></Style>""")

        detections.forEach { d ->
            if (d.latitude != null && d.longitude != null) {
                val styleId = d.threatLevel.name.lowercase()
                // Escape all user-controlled content in CDATA to prevent XML injection
                val safeMac = d.macAddress?.let { escapeCdata(it) } ?: ""
                val safeSsid = d.ssid?.let { escapeCdata(it) } ?: ""
                val safeDeviceName = d.deviceName?.let { escapeCdata(it) } ?: ""

                sb.appendLine("""    <Placemark>""")
                sb.appendLine("""      <name>${escapeXml(d.deviceType.displayName)}</name>""")
                sb.appendLine("""      <description><![CDATA[
        <b>Device:</b> ${escapeXml(d.deviceType.displayName)}<br/>
        <b>Threat Level:</b> ${escapeXml(d.threatLevel.displayName)}<br/>
        <b>Protocol:</b> ${escapeXml(d.protocol.displayName)}<br/>
        <b>Signal:</b> ${d.rssi} dBm (${escapeXml(d.signalStrength.displayName)})<br/>
        <b>Detected:</b> ${csvDateFormat.format(Date(d.timestamp))}<br/>
        <b>Times Seen:</b> ${d.seenCount}<br/>
        ${if (safeMac.isNotEmpty()) "<b>MAC:</b> $safeMac<br/>" else ""}
        ${if (safeSsid.isNotEmpty()) "<b>SSID:</b> $safeSsid<br/>" else ""}
        ${if (safeDeviceName.isNotEmpty()) "<b>Name:</b> $safeDeviceName<br/>" else ""}
      ]]></description>""")
                sb.appendLine("""      <styleUrl>#$styleId</styleUrl>""")
                sb.appendLine("""      <Point>""")
                sb.appendLine("""        <coordinates>${d.longitude},${d.latitude},0</coordinates>""")
                sb.appendLine("""      </Point>""")
                sb.appendLine("""      <TimeStamp><when>${toIso8601(d.timestamp)}</when></TimeStamp>""")
                sb.appendLine("""    </Placemark>""")
            }
        }

        sb.appendLine("""  </Document>""")
        sb.appendLine("""</kml>""")

        return sb.toString()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Escape content inside CDATA sections.
     * CDATA sections end with "]]>" so we need to handle this sequence.
     */
    private fun escapeCdata(value: String): String {
        // CDATA sections cannot contain "]]>" - split it if present
        return value.replace("]]>", "]]]]><![CDATA[>")
    }

    private fun toIso8601(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }

    private fun saveToFile(content: String, filename: String): File {
        val exportDir = File(context.cacheDir, "exports")
        exportDir.mkdirs()

        // Clean up old exports
        cleanupOldExports(exportDir)

        val file = File(exportDir, filename)
        file.writeText(content)
        return file
    }

    /**
     * Save content to an encrypted file using hardware-backed AES-GCM encryption.
     */
    private fun saveEncryptedFile(content: ByteArray, filename: String): File {
        val exportDir = File(context.cacheDir, "exports")
        exportDir.mkdirs()

        // Clean up old exports
        cleanupOldExports(exportDir)

        val encryptedContent = encryptContent(content)
        val file = File(exportDir, filename)
        file.writeBytes(encryptedContent)

        // Clear original content from memory
        SecureMemory.clear(content)

        Log.d(TAG, "Saved encrypted export: $filename")
        return file
    }

    /**
     * Encrypt content using hardware-backed AES-GCM.
     * Format: [12-byte IV][encrypted data with GCM tag]
     */
    private fun encryptContent(data: ByteArray): ByteArray {
        val key = getOrCreateExportKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv // 12 bytes for GCM
        val encrypted = cipher.doFinal(data)

        // Prepend IV to encrypted data
        return iv + encrypted
    }

    /**
     * Decrypt content encrypted with encryptContent.
     */
    private fun decryptContent(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Encrypted data too short")
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(EXPORT_KEY_ALIAS)) {
            throw IllegalStateException("Export decryption key not found")
        }

        val key = keyStore.getKey(EXPORT_KEY_ALIAS, null) as SecretKey

        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(ciphertext)
    }

    /**
     * Get or create the hardware-backed export encryption key.
     */
    private fun getOrCreateExportKey(): SecretKey {
        return secureKeyManager.getOrCreateKey(
            EXPORT_KEY_ALIAS,
            SecureKeyManager.KeyProtectionConfig(
                requireUserAuthentication = false,
                requireUnlockedDevice = true
            )
        )
    }

    private fun cleanupOldExports(exportDir: File) {
        exportDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                file.delete()
                Log.d(TAG, "Cleaned up old export: ${file.name}")
            }
        }
    }

    private fun getShareableUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // Data classes for JSON export
    data class ExportData(
        val exportedAt: Long,
        val version: String,
        val detectionCount: Int,
        val detections: List<ExportDetection>
    )

    data class ExportDetection(
        val id: String,
        val timestamp: Long,
        val lastSeenTimestamp: Long,
        val deviceType: String,
        val threatLevel: String,
        val protocol: String,
        val detectionMethod: String,
        val rssi: Int,
        val signalStrength: String,
        val macAddress: String?,
        val ssid: String?,
        val deviceName: String?,
        val manufacturer: String?,
        val latitude: Double?,
        val longitude: Double?,
        val seenCount: Int,
        val isActive: Boolean
    )

    private fun Detection.toExportDetection() = ExportDetection(
        id = id,
        timestamp = timestamp,
        lastSeenTimestamp = lastSeenTimestamp,
        deviceType = deviceType.name,
        threatLevel = threatLevel.name,
        protocol = protocol.name,
        detectionMethod = detectionMethod.name,
        rssi = rssi,
        signalStrength = signalStrength.name,
        macAddress = macAddress,
        ssid = ssid,
        deviceName = deviceName,
        manufacturer = manufacturer,
        latitude = latitude,
        longitude = longitude,
        seenCount = seenCount,
        isActive = isActive
    )
}

package com.flockyou.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.flockyou.data.model.Detection
import com.flockyou.data.repository.DetectionRepository
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Use case for exporting detection data in various formats
 */
class ExportDetectionsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DetectionRepository
) {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val csvDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Export detections to CSV format
     */
    suspend fun exportToCsv(): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val detections = repository.allDetections.first()
            val csv = buildCsv(detections)
            val file = saveToFile(csv, "detections_${dateFormat.format(Date())}.csv")
            val uri = getShareableUri(file)
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Export detections to JSON format
     */
    suspend fun exportToJson(): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val detections = repository.allDetections.first()
            val json = gson.toJson(ExportData(
                exportedAt = System.currentTimeMillis(),
                version = "1.0",
                detectionCount = detections.size,
                detections = detections.map { it.toExportDetection() }
            ))
            val file = saveToFile(json, "detections_${dateFormat.format(Date())}.json")
            val uri = getShareableUri(file)
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Export detections to KML format for Google Earth
     */
    suspend fun exportToKml(): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val detections = repository.detectionsWithLocation.first()
            val kml = buildKml(detections)
            val file = saveToFile(kml, "detections_${dateFormat.format(Date())}.kml")
            val uri = getShareableUri(file)
            Result.success(uri)
        } catch (e: Exception) {
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

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun buildKml(detections: List<Detection>): String {
        val sb = StringBuilder()
        
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("""  <Document>""")
        sb.appendLine("""    <name>Flock You Detections</name>""")
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
                sb.appendLine("""    <Placemark>""")
                sb.appendLine("""      <name>${escapeXml(d.deviceType.displayName)}</name>""")
                sb.appendLine("""      <description><![CDATA[
        <b>Device:</b> ${d.deviceType.displayName}<br/>
        <b>Threat Level:</b> ${d.threatLevel.displayName}<br/>
        <b>Protocol:</b> ${d.protocol.displayName}<br/>
        <b>Signal:</b> ${d.rssi} dBm (${d.signalStrength.displayName})<br/>
        <b>Detected:</b> ${csvDateFormat.format(Date(d.timestamp))}<br/>
        <b>Times Seen:</b> ${d.seenCount}<br/>
        ${d.macAddress?.let { "<b>MAC:</b> $it<br/>" } ?: ""}
        ${d.ssid?.let { "<b>SSID:</b> $it<br/>" } ?: ""}
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

    private fun toIso8601(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }

    private fun saveToFile(content: String, filename: String): File {
        val exportDir = File(context.cacheDir, "exports")
        exportDir.mkdirs()
        
        // Clean up old exports
        exportDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                file.delete()
            }
        }
        
        val file = File(exportDir, filename)
        file.writeText(content)
        return file
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

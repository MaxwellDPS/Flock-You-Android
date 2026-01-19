package com.flockyou.detection.framework

import android.util.Log

/**
 * BLE Beacon Protocol Parsers
 *
 * Parses raw BLE advertisement data into structured beacon formats:
 * - Apple iBeacon
 * - Google Eddystone (UID, URL, TLM, EID)
 * - AltBeacon
 * - Apple Find My Network
 * - Exposure Notification (for unwanted tracking detection)
 */
object BeaconParser {

    private const val TAG = "BeaconParser"

    // ============================================================================
    // iBeacon Parsing
    // ============================================================================

    /**
     * Apple iBeacon advertisement format:
     * - Manufacturer ID: 0x004C (Apple)
     * - Byte 0-1: iBeacon prefix (0x0215)
     * - Byte 2-17: Proximity UUID (16 bytes)
     * - Byte 18-19: Major (2 bytes, big-endian)
     * - Byte 20-21: Minor (2 bytes, big-endian)
     * - Byte 22: TX Power (1 byte, signed)
     */
    private const val IBEACON_PREFIX = 0x0215
    private const val IBEACON_LENGTH = 23

    /**
     * Parse iBeacon from manufacturer data
     * @param manufacturerId BLE manufacturer ID
     * @param manufacturerData Raw manufacturer-specific data
     * @param rssi Received signal strength
     * @return Parsed iBeacon data or null if not an iBeacon
     */
    fun parseIBeacon(manufacturerId: Int, manufacturerData: ByteArray, rssi: Int): IBeaconData? {
        // Must be Apple manufacturer ID
        if (manufacturerId != ManufacturerIds.APPLE) return null

        // Check minimum length
        if (manufacturerData.size < IBEACON_LENGTH) return null

        // Check iBeacon prefix
        val prefix = ((manufacturerData[0].toInt() and 0xFF) shl 8) or
                     (manufacturerData[1].toInt() and 0xFF)
        if (prefix != IBEACON_PREFIX) return null

        try {
            // Extract UUID (bytes 2-17)
            val uuidBytes = manufacturerData.sliceArray(2..17)
            val uuid = formatUuid(uuidBytes)

            // Extract Major (bytes 18-19, big-endian)
            val major = ((manufacturerData[18].toInt() and 0xFF) shl 8) or
                        (manufacturerData[19].toInt() and 0xFF)

            // Extract Minor (bytes 20-21, big-endian)
            val minor = ((manufacturerData[20].toInt() and 0xFF) shl 8) or
                        (manufacturerData[21].toInt() and 0xFF)

            // Extract TX Power (byte 22, signed)
            val txPower = manufacturerData[22].toInt()

            return IBeaconData(
                uuid = uuid,
                major = major,
                minor = minor,
                txPower = txPower,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse iBeacon: ${e.message}")
            return null
        }
    }

    // ============================================================================
    // Eddystone Parsing
    // ============================================================================

    /**
     * Eddystone Service UUID: 0xFEAA
     */
    const val EDDYSTONE_SERVICE_UUID = "0000FEAA-0000-1000-8000-00805F9B34FB"
    const val EDDYSTONE_SERVICE_UUID_16 = "FEAA"

    // Eddystone frame types
    private const val EDDYSTONE_UID = 0x00
    private const val EDDYSTONE_URL = 0x10
    private const val EDDYSTONE_TLM = 0x20
    private const val EDDYSTONE_EID = 0x30

    /**
     * Parse Eddystone frame from service data
     * @param serviceData Raw service data for Eddystone UUID
     * @param rssi Received signal strength
     * @return Parsed Eddystone frame or null
     */
    fun parseEddystone(serviceData: ByteArray, rssi: Int): EddystoneFrame? {
        if (serviceData.isEmpty()) return null

        return when (serviceData[0].toInt() and 0xFF) {
            EDDYSTONE_UID -> parseEddystoneUid(serviceData, rssi)
            EDDYSTONE_URL -> parseEddystoneUrl(serviceData, rssi)
            EDDYSTONE_TLM -> parseEddystoneTlm(serviceData, rssi)
            EDDYSTONE_EID -> parseEddystoneEid(serviceData, rssi)
            else -> null
        }
    }

    /**
     * Parse Eddystone-UID frame
     * Format:
     * - Byte 0: Frame type (0x00)
     * - Byte 1: TX Power
     * - Byte 2-11: Namespace (10 bytes)
     * - Byte 12-17: Instance (6 bytes)
     * - Byte 18-19: Reserved (optional)
     */
    private fun parseEddystoneUid(data: ByteArray, rssi: Int): EddystoneFrame.UID? {
        if (data.size < 18) return null

        try {
            val txPower = data[1].toInt()
            val namespace = data.sliceArray(2..11)
            val instance = data.sliceArray(12..17)

            return EddystoneFrame.UID(
                namespace = namespace,
                instance = instance,
                txPower = txPower,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Eddystone-UID: ${e.message}")
            return null
        }
    }

    /**
     * Parse Eddystone-URL frame
     * Format:
     * - Byte 0: Frame type (0x10)
     * - Byte 1: TX Power
     * - Byte 2: URL Scheme
     * - Byte 3+: Encoded URL
     */
    private fun parseEddystoneUrl(data: ByteArray, rssi: Int): EddystoneFrame.URL? {
        if (data.size < 4) return null

        try {
            val txPower = data[1].toInt()
            val scheme = URL_SCHEMES.getOrNull(data[2].toInt() and 0xFF) ?: "http://"

            val encodedUrl = StringBuilder(scheme)
            for (i in 3 until data.size) {
                val byte = data[i].toInt() and 0xFF
                val expansion = URL_CODES.getOrNull(byte)
                if (expansion != null) {
                    encodedUrl.append(expansion)
                } else if (byte in 0x20..0x7E) {
                    encodedUrl.append(byte.toChar())
                }
            }

            return EddystoneFrame.URL(
                url = encodedUrl.toString(),
                txPower = txPower,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Eddystone-URL: ${e.message}")
            return null
        }
    }

    /**
     * Parse Eddystone-TLM frame
     * Format:
     * - Byte 0: Frame type (0x20)
     * - Byte 1: Version
     * - Byte 2-3: Battery voltage (mV, big-endian)
     * - Byte 4-5: Temperature (8.8 fixed-point, big-endian)
     * - Byte 6-9: Advertisement count (big-endian)
     * - Byte 10-13: Uptime (100ms units, big-endian)
     */
    private fun parseEddystoneTlm(data: ByteArray, rssi: Int): EddystoneFrame.TLM? {
        if (data.size < 14) return null

        try {
            val battery = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)

            // Temperature: signed 8.8 fixed-point
            val tempWhole = data[4].toInt()
            val tempFrac = (data[5].toInt() and 0xFF) / 256.0f
            val temperature = tempWhole + tempFrac

            val advCount = ((data[6].toLong() and 0xFF) shl 24) or
                           ((data[7].toLong() and 0xFF) shl 16) or
                           ((data[8].toLong() and 0xFF) shl 8) or
                           (data[9].toLong() and 0xFF)

            val uptimeTicks = ((data[10].toLong() and 0xFF) shl 24) or
                              ((data[11].toLong() and 0xFF) shl 16) or
                              ((data[12].toLong() and 0xFF) shl 8) or
                              (data[13].toLong() and 0xFF)
            val uptimeSeconds = uptimeTicks / 10  // 100ms units to seconds

            return EddystoneFrame.TLM(
                batteryMillivolts = battery,
                temperatureCelsius = temperature,
                advertisementCount = advCount,
                uptimeSeconds = uptimeSeconds,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Eddystone-TLM: ${e.message}")
            return null
        }
    }

    /**
     * Parse Eddystone-EID frame
     * Format:
     * - Byte 0: Frame type (0x30)
     * - Byte 1: TX Power
     * - Byte 2-9: Ephemeral ID (8 bytes, rotates)
     */
    private fun parseEddystoneEid(data: ByteArray, rssi: Int): EddystoneFrame.EID? {
        if (data.size < 10) return null

        try {
            val txPower = data[1].toInt()
            val eid = data.sliceArray(2..9)

            return EddystoneFrame.EID(
                ephemeralId = eid,
                txPower = txPower,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Eddystone-EID: ${e.message}")
            return null
        }
    }

    // ============================================================================
    // Apple Find My Network Parsing
    // ============================================================================

    /**
     * Apple Find My Network service UUID
     */
    const val FIND_MY_SERVICE_UUID = "7DFC9000-7D1C-4951-86AA-8D9728F8D66C"

    /**
     * Apple Find My advertisement payload analysis
     * Find My devices use rotating public keys and specific advertisement patterns
     */
    data class FindMyData(
        val publicKey: ByteArray,
        val status: FindMyStatus,
        val batteryLevel: BatteryLevel?,
        val rssi: Int
    ) {
        enum class FindMyStatus {
            UNPAIRED,        // Device not paired (looking for owner)
            PAIRED_NEARBY,   // Paired device, owner nearby
            SEPARATED,       // Separated from owner
            UNKNOWN
        }

        enum class BatteryLevel {
            FULL, MEDIUM, LOW, CRITICAL, UNKNOWN
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FindMyData) return false
            return publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    /**
     * Parse Apple Find My advertisement
     */
    fun parseFindMy(manufacturerData: ByteArray, rssi: Int): FindMyData? {
        // Find My uses specific payload structure
        // This is a simplified parser - full implementation requires crypto analysis
        if (manufacturerData.size < 3) return null

        try {
            val type = manufacturerData[0].toInt() and 0xFF

            // Find My payloads typically start with specific type indicators
            val status = when {
                type == 0x07 -> FindMyData.FindMyStatus.SEPARATED
                type == 0x05 -> FindMyData.FindMyStatus.PAIRED_NEARBY
                type == 0x02 -> FindMyData.FindMyStatus.UNPAIRED
                else -> FindMyData.FindMyStatus.UNKNOWN
            }

            // Extract public key portion (varies by payload type)
            val keyStart = if (type == 0x07) 2 else 1
            val keyEnd = minOf(keyStart + 22, manufacturerData.size)
            val publicKey = manufacturerData.sliceArray(keyStart until keyEnd)

            // Battery level might be encoded in status byte
            val batteryLevel = if (manufacturerData.size > 2) {
                val batteryBits = (manufacturerData[2].toInt() and 0x0C) shr 2
                when (batteryBits) {
                    0 -> FindMyData.BatteryLevel.FULL
                    1 -> FindMyData.BatteryLevel.MEDIUM
                    2 -> FindMyData.BatteryLevel.LOW
                    3 -> FindMyData.BatteryLevel.CRITICAL
                    else -> FindMyData.BatteryLevel.UNKNOWN
                }
            } else null

            return FindMyData(
                publicKey = publicKey,
                status = status,
                batteryLevel = batteryLevel,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Find My data: ${e.message}")
            return null
        }
    }

    // ============================================================================
    // Exposure Notification / Unwanted Tracking Detection
    // ============================================================================

    /**
     * Exposure Notification Service UUID (also used for unwanted tracking alerts)
     * This UUID is broadcast by AirTags and other Find My devices when they've been
     * separated from their owner for extended periods.
     */
    const val EXPOSURE_NOTIFICATION_UUID = "0000FD6F-0000-1000-8000-00805F9B34FB"
    const val EXPOSURE_NOTIFICATION_UUID_16 = "FD6F"

    /**
     * Parsed exposure notification / unwanted tracking data
     */
    data class UnwantedTrackingData(
        val rollingProximityId: ByteArray,
        val encryptedMetadata: ByteArray,
        val isUnwantedTrackingAlert: Boolean,
        val rssi: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UnwantedTrackingData) return false
            return rollingProximityId.contentEquals(other.rollingProximityId)
        }

        override fun hashCode(): Int = rollingProximityId.contentHashCode()
    }

    /**
     * Parse Exposure Notification service data
     * This can indicate:
     * 1. COVID-19 exposure notification
     * 2. Unwanted tracking alert from Find My device
     */
    fun parseExposureNotification(serviceData: ByteArray, rssi: Int): UnwantedTrackingData? {
        // Exposure notification format:
        // - Bytes 0-15: Rolling Proximity Identifier
        // - Bytes 16+: Associated Encrypted Metadata (optional)
        if (serviceData.size < 16) return null

        try {
            val rpi = serviceData.sliceArray(0..15)
            val metadata = if (serviceData.size > 16) {
                serviceData.sliceArray(16 until serviceData.size)
            } else {
                ByteArray(0)
            }

            // Heuristic: Unwanted tracking alerts tend to have specific patterns
            // This is a simplified check - real implementation needs more analysis
            val isUnwantedTracking = serviceData.size >= 20

            return UnwantedTrackingData(
                rollingProximityId = rpi,
                encryptedMetadata = metadata,
                isUnwantedTrackingAlert = isUnwantedTracking,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Exposure Notification: ${e.message}")
            return null
        }
    }

    // ============================================================================
    // AltBeacon Parsing
    // ============================================================================

    /**
     * AltBeacon format (open specification)
     * - Byte 0-1: Beacon Code (0xBEAC)
     * - Byte 2-21: Beacon ID (20 bytes)
     * - Byte 22: Reference RSSI
     * - Byte 23: Manufacturer Reserved
     */
    private const val ALTBEACON_CODE = 0xBEAC

    data class AltBeaconData(
        val beaconId: ByteArray,     // 20-byte beacon ID
        val referenceRssi: Int,       // TX power at 1m
        val manufacturerReserved: Int,
        val rssi: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AltBeaconData) return false
            return beaconId.contentEquals(other.beaconId)
        }

        override fun hashCode(): Int = beaconId.contentHashCode()
    }

    fun parseAltBeacon(manufacturerData: ByteArray, rssi: Int): AltBeaconData? {
        if (manufacturerData.size < 24) return null

        try {
            val code = ((manufacturerData[0].toInt() and 0xFF) shl 8) or
                       (manufacturerData[1].toInt() and 0xFF)
            if (code != ALTBEACON_CODE) return null

            val beaconId = manufacturerData.sliceArray(2..21)
            val refRssi = manufacturerData[22].toInt()
            val mfrReserved = manufacturerData[23].toInt() and 0xFF

            return AltBeaconData(
                beaconId = beaconId,
                referenceRssi = refRssi,
                manufacturerReserved = mfrReserved,
                rssi = rssi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse AltBeacon: ${e.message}")
            return null
        }
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    /**
     * Format 16-byte UUID as standard UUID string
     */
    private fun formatUuid(bytes: ByteArray): String {
        if (bytes.size != 16) return bytes.toHexString()

        return buildString {
            // UUID format: 8-4-4-4-12
            append(bytes.sliceArray(0..3).toHexString())
            append("-")
            append(bytes.sliceArray(4..5).toHexString())
            append("-")
            append(bytes.sliceArray(6..7).toHexString())
            append("-")
            append(bytes.sliceArray(8..9).toHexString())
            append("-")
            append(bytes.sliceArray(10..15).toHexString())
        }
    }

    // Eddystone URL encoding tables
    private val URL_SCHEMES = arrayOf(
        "http://www.",
        "https://www.",
        "http://",
        "https://"
    )

    private val URL_CODES = mapOf(
        0x00 to ".com/",
        0x01 to ".org/",
        0x02 to ".edu/",
        0x03 to ".net/",
        0x04 to ".info/",
        0x05 to ".biz/",
        0x06 to ".gov/",
        0x07 to ".com",
        0x08 to ".org",
        0x09 to ".edu",
        0x0A to ".net",
        0x0B to ".info",
        0x0C to ".biz",
        0x0D to ".gov"
    ).let { map ->
        Array(256) { map[it] }
    }

    /**
     * Detect beacon type from advertisement data
     */
    fun detectBeaconType(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<String>,
        serviceData: Map<String, ByteArray>
    ): BeaconProtocolType {
        // Check for iBeacon
        if (manufacturerId == ManufacturerIds.APPLE && manufacturerData != null) {
            if (manufacturerData.size >= 2) {
                val prefix = ((manufacturerData[0].toInt() and 0xFF) shl 8) or
                             (manufacturerData[1].toInt() and 0xFF)
                if (prefix == IBEACON_PREFIX) return BeaconProtocolType.IBEACON
            }
        }

        // Check for Eddystone
        if (serviceUuids.any { it.contains("FEAA", ignoreCase = true) }) {
            val eddystoneData = serviceData.entries.find {
                it.key.contains("FEAA", ignoreCase = true)
            }?.value

            if (eddystoneData != null && eddystoneData.isNotEmpty()) {
                return when (eddystoneData[0].toInt() and 0xFF) {
                    EDDYSTONE_UID -> BeaconProtocolType.EDDYSTONE_UID
                    EDDYSTONE_URL -> BeaconProtocolType.EDDYSTONE_URL
                    EDDYSTONE_TLM -> BeaconProtocolType.EDDYSTONE_TLM
                    EDDYSTONE_EID -> BeaconProtocolType.EDDYSTONE_EID
                    else -> BeaconProtocolType.UNKNOWN
                }
            }
        }

        // Check for Find My
        if (serviceUuids.any { it.contains("7DFC9000", ignoreCase = true) }) {
            return BeaconProtocolType.FIND_MY
        }

        // Check for Exposure Notification / Unwanted Tracking
        if (serviceUuids.any { it.contains("FD6F", ignoreCase = true) }) {
            return BeaconProtocolType.EXPOSURE_NOTIFICATION
        }

        // Check for AltBeacon
        if (manufacturerData != null && manufacturerData.size >= 2) {
            val code = ((manufacturerData[0].toInt() and 0xFF) shl 8) or
                       (manufacturerData[1].toInt() and 0xFF)
            if (code == ALTBEACON_CODE) return BeaconProtocolType.ALTBEACON
        }

        // Check for Fast Pair
        if (manufacturerId == ManufacturerIds.GOOGLE) {
            return BeaconProtocolType.FAST_PAIR
        }

        // Check for Swift Pair
        if (manufacturerId == ManufacturerIds.MICROSOFT) {
            return BeaconProtocolType.SWIFT_PAIR
        }

        return BeaconProtocolType.UNKNOWN
    }
}

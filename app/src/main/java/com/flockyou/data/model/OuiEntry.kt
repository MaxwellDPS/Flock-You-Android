package com.flockyou.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an IEEE OUI (Organizationally Unique Identifier) entry.
 * The OUI is the first 3 octets (24 bits) of a MAC address that identifies
 * the manufacturer/vendor.
 */
@Entity(
    tableName = "oui_entries",
    indices = [
        Index(value = ["ouiPrefix"], unique = true)
    ]
)
data class OuiEntry(
    @PrimaryKey
    val ouiPrefix: String,  // Format: "AA:BB:CC" (uppercase, colon-separated)
    val organizationName: String,
    val registry: String = "MA-L",  // MA-L (large), MA-M (medium), MA-S (small)
    val address: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

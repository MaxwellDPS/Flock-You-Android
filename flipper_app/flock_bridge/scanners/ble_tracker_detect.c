#include "ble_scanner_internal.h"
#include <string.h>

// ============================================================================
// BLE Tracker Detection Module
// ============================================================================
// Handles tracker/spam identification and following detection:
// - AirTag, Tile, SmartTag, Chipolo identification
// - BLE spam detection (Flipper attacks)
// - Device history tracking for "following" detection
// - Advertisement data parsing from external hardware
//
// IMPORTANT: This module provides the analysis functions that work with
// advertisement data. Since the Flipper Zero cannot natively scan BLE,
// data must come from:
// 1. External hardware (ESP32/nRF) via ble_scanner_process_advertisement()
// 2. RF test mode provides only activity detection (no advertisement parsing)
//
// For full tracker detection (AirTags, Tiles, etc.), external hardware is required.

// ============================================================================
// Service UUIDs for Tracker Detection
// ============================================================================

// Tile tracker service UUID (16-bit: 0xFEED, 0xFEEC)
#define TILE_SERVICE_UUID_FEED      0xFEED
#define TILE_SERVICE_UUID_FEEC      0xFEEC

// Chipolo service UUID
#define CHIPOLO_SERVICE_UUID        0xFE50

// ============================================================================
// Tracker/Spam Name Lookup
// ============================================================================

const char* ble_scanner_get_tracker_name(BleTrackerType type) {
    switch (type) {
    case BleTrackerAirTag: return "AirTag";
    case BleTrackerFindMy: return "FindMy";
    case BleTrackerTile: return "Tile";
    case BleTrackerSmartTag: return "SmartTag";
    case BleTrackerChipolo: return "Chipolo";
    case BleTrackerUnknown: return "Unknown Tracker";
    default: return "None";
    }
}

const char* ble_scanner_get_spam_name(BleSpamType type) {
    switch (type) {
    case BleSpamApplePopup: return "Apple Popup Spam";
    case BleSpamAndroidPopup: return "Android Popup Spam";
    case BleSpamWindowsPopup: return "Windows Popup Spam";
    case BleSpamDenialOfService: return "BLE DoS";
    default: return "None";
    }
}

// ============================================================================
// Tracker Identification
// ============================================================================

BleTrackerType ble_scanner_identify_tracker(
    const uint8_t* manufacturer_data,
    size_t data_len,
    uint16_t manufacturer_id) {

    if (!manufacturer_data || data_len < 2) return BleTrackerNone;

    // Apple devices
    if (manufacturer_id == MANUFACTURER_APPLE && data_len >= 3) {
        uint8_t type = manufacturer_data[0];

        // AirTag / FindMy
        if (type == APPLE_TYPE_AIRTAG || type == APPLE_TYPE_FINDMY) {
            // AirTag has specific pattern
            if (data_len >= 25) {
                return BleTrackerAirTag;
            }
            return BleTrackerFindMy;
        }
    }

    // Samsung SmartTag
    if (manufacturer_id == MANUFACTURER_SAMSUNG && data_len >= 4) {
        // SmartTag has specific service data pattern
        // Check for SmartThings Find service
        return BleTrackerSmartTag;
    }

    // Tile - uses service UUID 0xFEED
    // This would need service data check, not manufacturer data

    // Chipolo - uses specific manufacturer data pattern
    // Check for Chipolo signature

    // Generic tracker heuristic: small device, regular beacons, specific RSSI patterns
    // Could add ML-based detection here

    return BleTrackerNone;
}

// ============================================================================
// Spam Identification
// ============================================================================

BleSpamType ble_scanner_identify_spam(
    const uint8_t* manufacturer_data,
    size_t data_len,
    uint16_t manufacturer_id) {

    if (!manufacturer_data || data_len < 2) return BleSpamNone;

    // Apple popup spam (fake AirPods, etc.)
    if (manufacturer_id == MANUFACTURER_APPLE) {
        uint8_t type = manufacturer_data[0];

        // Proximity pairing spam
        if (type == APPLE_TYPE_AIRPODS || type == APPLE_TYPE_NEARBY) {
            // Check for known spam patterns
            // Flipper BLE spam uses specific byte patterns
            if (data_len >= 27) {
                // Check for non-standard length or patterns
                // Real AirPods have specific structure
                // Spam often has randomized or invalid data

                // Heuristic: rapid advertising with changing data = spam
                return BleSpamApplePopup;
            }
        }
    }

    // Google FastPair spam
    if (manufacturer_id == MANUFACTURER_GOOGLE) {
        // FastPair service data indicates pairing request
        // Spam sends many different model IDs rapidly
        return BleSpamAndroidPopup;
    }

    // Microsoft Swift Pair spam
    if (manufacturer_id == MANUFACTURER_MICROSOFT) {
        return BleSpamWindowsPopup;
    }

    return BleSpamNone;
}

// ============================================================================
// Following Detection
// ============================================================================

bool ble_scanner_check_device_following(BleScanner* scanner, const uint8_t* mac) {
    if (!scanner->config.detect_following) return false;

    uint32_t now = furi_get_tick();

    // Find or create history entry
    DeviceHistoryEntry* entry = NULL;
    int free_slot = -1;

    for (int i = 0; i < MAX_DEVICE_HISTORY; i++) {
        if (scanner->device_history[i].valid) {
            if (memcmp(scanner->device_history[i].mac, mac, 6) == 0) {
                entry = &scanner->device_history[i];
                break;
            }
        } else if (free_slot < 0) {
            free_slot = i;
        }
    }

    if (!entry) {
        if (free_slot < 0) {
            // History full, overwrite oldest
            free_slot = 0;
        }
        entry = &scanner->device_history[free_slot];
        memcpy(entry->mac, mac, 6);
        entry->timestamp_count = 0;
        entry->timestamp_head = 0;
        entry->valid = true;
        // Only increment count if we're using a new slot, not overwriting
        if (scanner->history_count < MAX_DEVICE_HISTORY) {
            scanner->history_count++;
        }
    }

    // Add timestamp
    entry->timestamps[entry->timestamp_head] = now;
    entry->timestamp_head = (entry->timestamp_head + 1) % 8;
    if (entry->timestamp_count < 8) {
        entry->timestamp_count++;
    }

    // Check if device has been seen multiple times in time window
    if (entry->timestamp_count >= FOLLOWING_THRESHOLD_COUNT) {
        uint32_t oldest = entry->timestamps[(entry->timestamp_head + 8 - entry->timestamp_count) % 8];
        if ((now - oldest) <= FOLLOWING_TIME_WINDOW_MS) {
            return true;  // Device is following
        }
    }

    return false;
}

// ============================================================================
// Advertisement Processing (for external hardware data)
// ============================================================================
// This function processes BLE advertisement data received from external hardware
// (ESP32, nRF, etc.) and performs tracker/spam identification.
//
// IMPORTANT: This is the primary entry point for BLE device analysis. Since
// the Flipper Zero cannot perform native BLE scanning, external hardware must
// call this function with parsed advertisement data.
//
// Expected data flow:
// 1. ESP32/nRF receives BLE advertisement
// 2. External module sends data to Flipper via UART (see external_radio.h)
// 3. ExternalRadioManager receives ExtRadioRespBleDevice response
// 4. Handler calls ble_scanner_process_advertisement() with parsed data
// 5. This function identifies trackers/spam and invokes the scanner callback
// 6. Callback reaches scheduler_ble_callback_impl() which notifies the app

void ble_scanner_process_advertisement(
    BleScanner* scanner,
    const uint8_t* address,
    uint8_t address_type,
    int8_t rssi,
    const uint8_t* adv_data,
    size_t adv_data_len) {

    if (!scanner || !address) return;

    // Filter by RSSI threshold
    if (rssi < scanner->config.rssi_threshold) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Parse advertisement data
    BleDeviceExtended device = {0};
    memcpy(device.base.mac_address, address, 6);
    device.base.rssi = rssi;
    device.base.address_type = address_type;
    device.last_seen = furi_get_tick();

    // Parse AD structures
    size_t offset = 0;
    uint16_t manufacturer_id = 0;
    const uint8_t* manufacturer_data = NULL;
    size_t manufacturer_data_len = 0;
    bool has_tile_service = false;
    bool has_chipolo_service = false;

    while (adv_data && offset < adv_data_len) {
        uint8_t len = adv_data[offset];
        if (len == 0 || offset + len >= adv_data_len) break;

        uint8_t type = adv_data[offset + 1];
        const uint8_t* data = &adv_data[offset + 2];
        size_t data_len = len - 1;

        switch (type) {
        case 0x09:  // Complete Local Name
        case 0x08:  // Shortened Local Name
            if (data_len < sizeof(device.base.name)) {
                memcpy(device.base.name, data, data_len);
            }
            break;

        case 0xFF:  // Manufacturer Specific Data
            if (data_len >= 2) {
                manufacturer_id = data[0] | (data[1] << 8);
                manufacturer_data = data + 2;
                manufacturer_data_len = data_len - 2;

                device.base.manufacturer_id[0] = data[0];
                device.base.manufacturer_id[1] = data[1];
                device.base.manufacturer_data_len = (data_len - 2 < 32) ? data_len - 2 : 32;
                memcpy(device.base.manufacturer_data, data + 2, device.base.manufacturer_data_len);
            }
            break;

        case 0x02:  // Incomplete 16-bit UUIDs
        case 0x03:  // Complete 16-bit UUIDs
            // Parse 16-bit service UUIDs for tracker detection
            for (size_t i = 0; i + 1 < data_len; i += 2) {
                uint16_t uuid16 = data[i] | (data[i + 1] << 8);
                if (uuid16 == TILE_SERVICE_UUID_FEED || uuid16 == TILE_SERVICE_UUID_FEEC) {
                    has_tile_service = true;
                }
                if (uuid16 == CHIPOLO_SERVICE_UUID) {
                    has_chipolo_service = true;
                }
            }
            break;

        case 0x06:  // Incomplete 128-bit UUIDs
        case 0x07:  // Complete 128-bit UUIDs
            if (data_len >= 16 && device.base.service_uuid_count < 4) {
                memcpy(device.base.service_uuids[device.base.service_uuid_count], data, 16);
                device.base.service_uuid_count++;
            }
            break;

        case 0x01:  // Flags
            device.base.is_connectable = (data[0] & 0x02) != 0;
            break;
        }

        offset += len + 1;
    }

    // Identify tracker (using manufacturer data)
    device.tracker_type = ble_scanner_identify_tracker(
        manufacturer_data, manufacturer_data_len, manufacturer_id);

    // Also check service UUIDs for trackers that don't use manufacturer data
    if (device.tracker_type == BleTrackerNone) {
        if (has_tile_service) {
            device.tracker_type = BleTrackerTile;
            FURI_LOG_D(TAG, "Tile tracker detected via service UUID");
        } else if (has_chipolo_service) {
            device.tracker_type = BleTrackerChipolo;
            FURI_LOG_D(TAG, "Chipolo tracker detected via service UUID");
        }
    }

    // Identify spam
    device.spam_type = ble_scanner_identify_spam(
        manufacturer_data, manufacturer_data_len, manufacturer_id);

    // Check for following
    device.is_following = ble_scanner_check_device_following(scanner, address);

    // Update stats
    scanner->stats.total_devices_seen++;
    if (device.tracker_type != BleTrackerNone) {
        scanner->stats.trackers_detected++;
    }
    if (device.spam_type != BleSpamNone) {
        scanner->stats.spam_detected++;
    }

    // Invoke callback
    if (scanner->config.callback) {
        scanner->config.callback(&device, scanner->config.callback_context);
    }

    if (device.tracker_type != BleTrackerNone) {
        FURI_LOG_I(TAG, "Tracker detected: %s (RSSI: %d)",
            ble_scanner_get_tracker_name(device.tracker_type), rssi);
    }
    if (device.spam_type != BleSpamNone) {
        FURI_LOG_W(TAG, "BLE Spam detected: %s",
            ble_scanner_get_spam_name(device.spam_type));
    }

    furi_mutex_release(scanner->mutex);
}

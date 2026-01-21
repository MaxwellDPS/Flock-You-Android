#include "ble_scanner.h"
#include <bt/bt_service/bt.h>
#include <furi_hal_bt.h>
#include <gap.h>
#include <string.h>
#include <stdlib.h>

#define TAG "BleScanner"

// ============================================================================
// BLE Scanner Implementation Notes
// ============================================================================
// The Flipper Zero SDK does not expose BLE scanning functions (gap_start_scan,
// gap_stop_scan). These are internal firmware functions not available to FAPs.
//
// For full BLE device discovery with advertisement parsing, an external radio
// (ESP32 with BLE support or nRF) is required via the ExternalRadioManager.
//
// This scanner module provides:
// - Basic signal detection using raw RF mode (RSSI only, no advertisement data)
// - Tracker and spam identification algorithms (for use with external radio data)
// - Device history tracking for "following" detection
//
// When external hardware provides BLE advertisement data, use the
// ble_scanner_identify_tracker() and ble_scanner_identify_spam() functions
// to analyze it.
// ============================================================================

// BLE channels for scanning (2.4 GHz band)
// Data channels: 0-36, Advertising channels: 37, 38, 39
#define BLE_ADV_CHANNEL_37  37  // 2402 MHz
#define BLE_ADV_CHANNEL_38  38  // 2426 MHz
#define BLE_ADV_CHANNEL_39  39  // 2480 MHz

// ============================================================================
// Manufacturer IDs
// ============================================================================

#define MANUFACTURER_APPLE      0x004C
#define MANUFACTURER_SAMSUNG    0x0075
#define MANUFACTURER_TILE       0x00E0  // Actually uses different approach
#define MANUFACTURER_MICROSOFT  0x0006
#define MANUFACTURER_GOOGLE     0x00E0

// Apple continuity message types
#define APPLE_TYPE_AIRDROP      0x05
#define APPLE_TYPE_AIRPODS      0x07
#define APPLE_TYPE_AIRPLAY      0x09
#define APPLE_TYPE_AIRTAG       0x12
#define APPLE_TYPE_NEARBY       0x10
#define APPLE_TYPE_FINDMY       0x12

// ============================================================================
// Device History for Following Detection
// ============================================================================

#define MAX_DEVICE_HISTORY 64
#define FOLLOWING_THRESHOLD_COUNT 5
#define FOLLOWING_TIME_WINDOW_MS 300000  // 5 minutes

typedef struct {
    uint8_t mac[6];
    uint32_t timestamps[8];      // Rolling buffer of detection times
    uint8_t timestamp_count;
    uint8_t timestamp_head;
    bool valid;
} DeviceHistoryEntry;

// ============================================================================
// Scanner Structure
// ============================================================================

struct BleScanner {
    BleScannerConfig config;
    BleScannerStats stats;

    // BT service
    Bt* bt;

    // State
    bool running;
    bool scanning_active;  // True when GAP scan is actually running
    uint32_t scan_start_time;

    // Device history for following detection
    DeviceHistoryEntry device_history[MAX_DEVICE_HISTORY];
    uint8_t history_count;

    // Scan results buffer (filled by GAP callback)
    BleDeviceExtended scan_results[32];
    uint8_t scan_result_count;

    // Thread and sync
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool should_stop;
};

// ============================================================================
// Tracker/Spam Identification
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

static bool check_device_following(BleScanner* scanner, const uint8_t* mac) {
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
// Call this when external hardware provides raw advertisement data.

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
            // Could parse service UUIDs here
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

    // Identify tracker
    device.tracker_type = ble_scanner_identify_tracker(
        manufacturer_data, manufacturer_data_len, manufacturer_id);

    // Identify spam
    device.spam_type = ble_scanner_identify_spam(
        manufacturer_data, manufacturer_data_len, manufacturer_id);

    // Check for following
    device.is_following = check_device_following(scanner, address);

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

// ============================================================================
// Worker Thread - Raw RF Mode
// ============================================================================
// Uses raw RF mode to detect BLE activity on advertising channels.
// This provides RSSI-only detection without advertisement data parsing.
// For full advertisement parsing, external hardware (ESP32) is required.

static int32_t ble_scanner_worker(void* context) {
    BleScanner* scanner = context;

    FURI_LOG_I(TAG, "BLE scanner worker started (raw RF mode)");

    uint8_t channels[] = {BLE_ADV_CHANNEL_37, BLE_ADV_CHANNEL_38, BLE_ADV_CHANNEL_39};
    uint8_t channel_count = sizeof(channels) / sizeof(channels[0]);
    uint8_t current_channel = 0;

    uint32_t elapsed = 0;
    uint32_t dwell_time_ms = 100;  // Time per channel

    while (!scanner->should_stop && elapsed < scanner->config.scan_duration_ms) {
        // Start receiving on current BLE advertising channel
        furi_hal_bt_start_rx(channels[current_channel]);

        // Dwell on this channel
        furi_delay_ms(dwell_time_ms);

        // Get RSSI measurement
        float rssi = furi_hal_bt_get_rssi();

        // Stop receiving
        furi_hal_bt_stop_rx();

        // If we detected significant signal strength, record it
        if (rssi > (float)scanner->config.rssi_threshold) {
            furi_mutex_acquire(scanner->mutex, FuriWaitForever);

            // Log the detection
            FURI_LOG_D(TAG, "BLE activity on ch%d: RSSI=%.1f dBm",
                channels[current_channel], (double)rssi);

            // Update stats - we can't identify individual devices in raw mode
            // Just track that we detected BLE activity
            scanner->stats.total_devices_seen++;

            furi_mutex_release(scanner->mutex);
        }

        // Move to next channel
        current_channel = (current_channel + 1) % channel_count;
        elapsed += dwell_time_ms;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->scanning_active = false;
    scanner->running = false;
    scanner->stats.scans_completed++;
    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "BLE scan completed (raw RF mode)");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

BleScanner* ble_scanner_alloc(void) {
    BleScanner* scanner = malloc(sizeof(BleScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(BleScanner));

    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.detect_trackers = true;
    scanner->config.detect_spam = true;
    scanner->config.detect_following = true;
    scanner->config.rssi_threshold = -85;
    scanner->config.scan_duration_ms = 2000;

    scanner->bt = furi_record_open(RECORD_BT);

    FURI_LOG_I(TAG, "BLE scanner allocated");
    return scanner;
}

void ble_scanner_free(BleScanner* scanner) {
    if (!scanner) return;

    ble_scanner_stop(scanner);

    if (scanner->bt) {
        furi_record_close(RECORD_BT);
    }
    if (scanner->mutex) {
        furi_mutex_free(scanner->mutex);
    }

    free(scanner);
    FURI_LOG_I(TAG, "BLE scanner freed");
}

void ble_scanner_configure(BleScanner* scanner, const BleScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(BleScannerConfig));
    furi_mutex_release(scanner->mutex);
}

bool ble_scanner_start(BleScanner* scanner) {
    if (!scanner || scanner->running) return false;

    FURI_LOG_I(TAG, "Starting BLE scan (%lu ms) - raw RF mode", scanner->config.scan_duration_ms);
    FURI_LOG_I(TAG, "Note: Full BLE scanning requires external hardware (ESP32)");

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Reset scan results
    scanner->scan_result_count = 0;
    scanner->running = true;
    scanner->should_stop = false;
    scanner->scanning_active = true;
    scanner->scan_start_time = furi_get_tick();

    furi_mutex_release(scanner->mutex);

    // Start worker thread for raw RF scanning
    scanner->worker_thread = furi_thread_alloc_ex(
        "BleScanWorker", 2048, ble_scanner_worker, scanner);
    furi_thread_start(scanner->worker_thread);

    FURI_LOG_I(TAG, "BLE scan started (raw RF mode - RSSI detection only)");
    return true;
}

void ble_scanner_stop(BleScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping BLE scan");

    scanner->should_stop = true;

    // Stop raw RF receive if active
    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    if (scanner->scanning_active) {
        furi_hal_bt_stop_rx();
        scanner->scanning_active = false;
    }
    furi_mutex_release(scanner->mutex);

    if (scanner->worker_thread) {
        furi_thread_join(scanner->worker_thread);
        furi_thread_free(scanner->worker_thread);
        scanner->worker_thread = NULL;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->running = false;
    furi_mutex_release(scanner->mutex);
}

bool ble_scanner_is_running(BleScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

void ble_scanner_get_stats(BleScanner* scanner, BleScannerStats* stats) {
    if (!scanner || !stats) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(stats, &scanner->stats, sizeof(BleScannerStats));
    furi_mutex_release(scanner->mutex);
}

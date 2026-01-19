#include "ble_scanner.h"
#include <bt/bt_service/bt.h>
#include <furi_hal_bt.h>
#include <string.h>
#include <stdlib.h>

#define TAG "BleScanner"

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
    uint32_t scan_start_time;

    // Device history for following detection
    DeviceHistoryEntry device_history[MAX_DEVICE_HISTORY];
    uint8_t history_count;

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
        scanner->history_count++;
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
// BLE Scan Callback (called by BT stack)
// ============================================================================

static void ble_scan_result_callback(
    const uint8_t* address,
    uint8_t address_type,
    int8_t rssi,
    const uint8_t* adv_data,
    size_t adv_data_len,
    void* context) {

    BleScanner* scanner = context;
    if (!scanner || !scanner->running) return;

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

    while (offset < adv_data_len) {
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
// Worker Thread
// ============================================================================

static int32_t ble_scanner_worker(void* context) {
    BleScanner* scanner = context;

    FURI_LOG_I(TAG, "BLE scanner worker started");

    // Wait for scan duration
    uint32_t elapsed = 0;
    while (!scanner->should_stop && elapsed < scanner->config.scan_duration_ms) {
        furi_delay_ms(100);
        elapsed += 100;
    }

    // Stop scan
    furi_hal_bt_stop_scan();

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->running = false;
    scanner->stats.scans_completed++;
    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "BLE scanner worker stopped");
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

    FURI_LOG_I(TAG, "Starting BLE scan (%lu ms)", scanner->config.scan_duration_ms);

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->running = true;
    scanner->should_stop = false;
    scanner->scan_start_time = furi_get_tick();

    // Start BLE scan
    // Note: This requires the BT to not be in serial mode
    // In practice, you'd need to coordinate with bt_serial
    furi_hal_bt_start_scan(ble_scan_result_callback, scanner);

    // Start worker thread to manage scan duration
    scanner->worker_thread = furi_thread_alloc_ex(
        "BleScanWorker", 1024, ble_scanner_worker, scanner);
    furi_thread_start(scanner->worker_thread);

    furi_mutex_release(scanner->mutex);

    return true;
}

void ble_scanner_stop(BleScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping BLE scan");

    scanner->should_stop = true;

    if (scanner->worker_thread) {
        furi_thread_join(scanner->worker_thread);
        furi_thread_free(scanner->worker_thread);
        scanner->worker_thread = NULL;
    }

    furi_hal_bt_stop_scan();

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

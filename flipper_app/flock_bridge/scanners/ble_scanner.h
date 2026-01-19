#pragma once

#include "../protocol/flock_protocol.h"
#include <furi.h>
#include <furi_hal.h>

// ============================================================================
// BLE Scanner
// ============================================================================
// Handles Bluetooth Low Energy scanning with:
// - Device discovery
// - Tracker detection (AirTag, Tile, SmartTag, etc.)
// - BLE spam detection (Flipper attacks)
// - Manufacturer data parsing

typedef struct BleScanner BleScanner;

// ============================================================================
// Tracker Types
// ============================================================================

typedef enum {
    BleTrackerNone = 0,
    BleTrackerAirTag,           // Apple AirTag
    BleTrackerFindMy,           // Apple Find My network device
    BleTrackerTile,             // Tile tracker
    BleTrackerSmartTag,         // Samsung SmartTag
    BleTrackerChipolo,          // Chipolo
    BleTrackerUnknown,          // Unknown tracker pattern
} BleTrackerType;

// ============================================================================
// BLE Spam Types (Flipper attacks)
// ============================================================================

typedef enum {
    BleSpamNone = 0,
    BleSpamApplePopup,          // Fake AirPods popup
    BleSpamAndroidPopup,        // Fake FastPair popup
    BleSpamWindowsPopup,        // Fake Swift Pair popup
    BleSpamDenialOfService,     // BLE DoS attack
} BleSpamType;

// ============================================================================
// Extended Device Info
// ============================================================================

typedef struct {
    FlockBleDevice base;
    BleTrackerType tracker_type;
    BleSpamType spam_type;
    uint32_t first_seen;
    uint32_t last_seen;
    uint8_t detection_count;
    bool is_following;          // Detected moving with user
} BleDeviceExtended;

// ============================================================================
// Callback
// ============================================================================

typedef void (*BleScanCallback)(
    const BleDeviceExtended* device,
    void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    bool detect_trackers;
    bool detect_spam;
    bool detect_following;       // Requires multiple scans over time
    int8_t rssi_threshold;       // Minimum RSSI to report (default: -85 dBm)
    uint32_t scan_duration_ms;   // How long to scan (default: 2000ms)

    BleScanCallback callback;
    void* callback_context;
} BleScannerConfig;

// ============================================================================
// Statistics
// ============================================================================

typedef struct {
    uint32_t total_devices_seen;
    uint32_t trackers_detected;
    uint32_t spam_detected;
    uint32_t scans_completed;
} BleScannerStats;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate BLE scanner.
 */
BleScanner* ble_scanner_alloc(void);

/**
 * Free BLE scanner.
 */
void ble_scanner_free(BleScanner* scanner);

/**
 * Configure the scanner.
 */
void ble_scanner_configure(
    BleScanner* scanner,
    const BleScannerConfig* config);

/**
 * Start a single scan burst.
 * Scan runs for configured duration, then stops.
 * Results delivered via callback during scan.
 */
bool ble_scanner_start(BleScanner* scanner);

/**
 * Stop scanning early.
 */
void ble_scanner_stop(BleScanner* scanner);

/**
 * Check if scanner is currently running.
 */
bool ble_scanner_is_running(BleScanner* scanner);

/**
 * Get scan statistics.
 */
void ble_scanner_get_stats(BleScanner* scanner, BleScannerStats* stats);

/**
 * Check if a device is a known tracker type.
 */
BleTrackerType ble_scanner_identify_tracker(
    const uint8_t* manufacturer_data,
    size_t data_len,
    uint16_t manufacturer_id);

/**
 * Check if advertisement is BLE spam.
 */
BleSpamType ble_scanner_identify_spam(
    const uint8_t* manufacturer_data,
    size_t data_len,
    uint16_t manufacturer_id);

/**
 * Get tracker type name.
 */
const char* ble_scanner_get_tracker_name(BleTrackerType type);

/**
 * Get spam type name.
 */
const char* ble_scanner_get_spam_name(BleSpamType type);

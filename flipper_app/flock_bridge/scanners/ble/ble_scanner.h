#pragma once

#include "../../protocol/flock_protocol.h"
#include <furi.h>
#include <furi_hal.h>

// ============================================================================
// BLE Scanner
// ============================================================================
// Handles Bluetooth Low Energy scanning and analysis:
// - Basic signal detection via raw RF mode (RSSI only)
// - Full advertisement parsing (when data from external hardware)
// - Tracker detection (AirTag, Tile, SmartTag, etc.)
// - BLE spam detection (Flipper attacks)
// - "Following" detection via device history tracking
//
// IMPORTANT - Flipper Zero BLE Scanning Limitations:
// ==================================================
// The Flipper Zero SDK does NOT expose BLE scanning (observer/central mode)
// to FAPs. The BLE stack is peripheral-only for external applications.
//
// This scanner provides TWO modes of operation:
//
// 1. RF Test Mode (Internal, Limited)
//    Uses furi_hal_bt_start_packet_rx() on BLE advertising channels to
//    detect BLE activity via packet counts and RSSI. This provides basic
//    activity detection but CANNOT parse advertisement data.
//    - Works: Activity detection, presence of nearby BLE devices
//    - Does NOT work: Device identification, tracker detection, names
//
// 2. External Hardware (Full Capability)
//    An ESP32 or nRF module connected via UART provides full BLE scanning.
//    When the external module sends ExtRadioRespBleDevice responses, call
//    ble_scanner_handle_external_device() to process the data.
//    - Works: Full tracker detection (AirTag, Tile, etc.), spam detection,
//      device names, following detection
//
// To enable external BLE scanning:
// 1. Configure ExternalRadioManager with an on_data callback
// 2. In the callback, route ExtRadioRespBleDevice to:
//    ble_scanner_handle_external_device(scanner, data, len)
// 3. Or use scheduler_external_radio_callback() which handles routing

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

/**
 * Process advertisement data from external hardware.
 *
 * Call this when external hardware (ESP32, etc.) provides raw BLE
 * advertisement data. The scanner will parse the data, identify
 * trackers/spam, update statistics, and invoke the callback.
 *
 * @param scanner       BleScanner instance
 * @param address       6-byte MAC address
 * @param address_type  Address type (public/random)
 * @param rssi         Signal strength in dBm
 * @param adv_data     Raw advertisement data (can be NULL)
 * @param adv_data_len Length of advertisement data
 */
void ble_scanner_process_advertisement(
    BleScanner* scanner,
    const uint8_t* address,
    uint8_t address_type,
    int8_t rssi,
    const uint8_t* adv_data,
    size_t adv_data_len);

/**
 * Handle BLE device data from external radio module.
 *
 * Call this when ExtRadioRespBleDevice is received from an external
 * ESP32/nRF module. The data format is:
 * - Bytes 0-5: MAC address
 * - Byte 6: Address type (0=public, 1=random)
 * - Byte 7: RSSI (signed int8)
 * - Bytes 8-9: Advertisement data length (big-endian)
 * - Bytes 10+: Advertisement data
 *
 * @param scanner BleScanner instance
 * @param data    Raw packet data from external radio
 * @param len     Length of data
 */
void ble_scanner_handle_external_device(
    BleScanner* scanner,
    const uint8_t* data,
    size_t len);

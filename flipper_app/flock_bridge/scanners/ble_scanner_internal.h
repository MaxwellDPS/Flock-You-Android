#pragma once

#include "ble_scanner.h"
#include <bt/bt_service/bt.h>
#include <furi_hal_bt.h>

// ============================================================================
// BLE Scanner Internal Header
// ============================================================================
// Shared definitions between ble_scanner.c and ble_tracker_detect.c
// This header is NOT part of the public API.
//
// IMPORTANT: Flipper Zero Limitations
// ====================================
// The Flipper Zero SDK does NOT expose BLE scanning (observer/central mode)
// to external applications (FAPs). The BLE stack is peripheral-only for FAPs.
//
// Available options for BLE detection:
// 1. RF Test Mode - Use furi_hal_bt_start_packet_rx() on BLE advertising
//    frequencies (2402, 2426, 2480 MHz) to detect BLE activity via RSSI.
//    This provides signal detection but NO advertisement data parsing.
//
// 2. External Hardware - ESP32 or nRF module connected via GPIO/UART can
//    perform full BLE scanning and send parsed advertisement data back.
//
// This implementation uses approach #1 for basic detection, and supports
// receiving parsed data from external hardware via ble_scanner_process_advertisement().

#define TAG "BleScanner"

// ============================================================================
// BLE Advertising Channel Frequencies (MHz for RF test mode)
// ============================================================================
// BLE uses 3 advertising channels at fixed frequencies

#define BLE_ADV_FREQ_CH37   2402  // Channel 37
#define BLE_ADV_FREQ_CH38   2426  // Channel 38
#define BLE_ADV_FREQ_CH39   2480  // Channel 39

// Channel numbers (for logging)
#define BLE_ADV_CHANNEL_37  37
#define BLE_ADV_CHANNEL_38  38
#define BLE_ADV_CHANNEL_39  39

// ============================================================================
// RF Test Mode Parameters
// ============================================================================
// The STM32WB BLE stack supports a test mode for regulatory compliance testing.
// We use this to listen for BLE packets on advertising channels.

// PHY modes for BLE
#define BLE_PHY_1M          0x01  // 1 Mbps
#define BLE_PHY_2M          0x02  // 2 Mbps

// Modulation index
#define BLE_MOD_INDEX_STD   0x00  // Standard modulation

// ============================================================================
// Manufacturer IDs
// ============================================================================

#define MANUFACTURER_APPLE      0x004C
#define MANUFACTURER_SAMSUNG    0x0075
#define MANUFACTURER_TILE       0x00E0
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
// RSSI Activity Detection
// ============================================================================
// Since we can't parse BLE advertisements natively, we track RSSI activity
// patterns on advertising channels to detect BLE device presence.

#define MAX_RSSI_SAMPLES 16  // Samples per channel per scan burst

typedef struct {
    uint16_t frequency_mhz;     // 2402, 2426, or 2480
    uint8_t channel;            // 37, 38, or 39
    int8_t rssi_samples[MAX_RSSI_SAMPLES];
    uint8_t sample_count;
    int8_t rssi_max;
    int8_t rssi_avg;
    uint32_t activity_count;    // Times we detected signal above threshold
} BleChannelActivity;

// ============================================================================
// Scanner Structure
// ============================================================================

struct BleScanner {
    BleScannerConfig config;
    BleScannerStats stats;

    // BT service handle
    Bt* bt;

    // State
    bool running;
    bool scanning_active;       // True when RF test mode is running
    uint32_t scan_start_time;
    bool bt_was_active;         // Track if BT was active before we started

    // Channel activity tracking (RSSI-based detection)
    BleChannelActivity channel_activity[3];  // One per advertising channel
    uint8_t current_channel_index;

    // Device history for following detection (when external data available)
    DeviceHistoryEntry device_history[MAX_DEVICE_HISTORY];
    uint8_t history_count;

    // Scan results buffer (filled by external hardware via process_advertisement)
    BleDeviceExtended scan_results[32];
    uint8_t scan_result_count;

    // Thread and sync
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool should_stop;
};

// ============================================================================
// Internal Functions (implemented in ble_tracker_detect.c)
// ============================================================================

/**
 * Check if a device appears to be following the user.
 * Updates device history and returns true if following pattern detected.
 */
bool ble_scanner_check_device_following(BleScanner* scanner, const uint8_t* mac);

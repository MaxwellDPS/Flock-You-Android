/**
 * Scheduler Internal Header
 *
 * Internal structures and declarations for the detection scheduler.
 * Not part of the public API.
 */

#pragma once

#include "detection_scheduler.h"
#include "subghz_scanner.h"
#include "ble_scanner.h"
#include "ir_scanner.h"
#include "nfc_scanner.h"
#include "wifi_scanner.h"
#include "../helpers/bt_serial.h"
#include "../helpers/usb_cdc.h"
#include <furi.h>

// Time-multiplex BLE scan settings
#define BLE_MULTIPLEX_SCAN_DURATION_MS 2000

// Time-multiplex IR scan settings
// IR scanning conflicts with USB CDC dual mode due to DMA/timer resource sharing
// We pause USB CDC, run IR scan burst, then resume USB CDC
#define IR_SCAN_DURATION_MS 3000     // IR burst scan duration
#define IR_SCAN_INTERVAL_MS 10000    // Time between IR scan bursts (when USB is active)

// Memory cleanup interval - increased from 10s to 60s
// 10s was too aggressive and interrupted active signal reception by recreating the receiver
// 60s provides periodic cleanup without significantly impacting detection rates
#define MEMORY_CLEANUP_INTERVAL_MS 60000

// ============================================================================
// Scheduler Structure (internal definition)
// ============================================================================

struct DetectionScheduler {
    SchedulerConfig config;
    SchedulerStats stats;

    // Internal scanners (Flipper's built-in radios)
    SubGhzScanner* subghz_internal;
    BleScanner* ble_internal;
    IrScanner* ir;
    FlockNfcScanner* nfc;

    // External radio manager
    ExternalRadioManager* external_radio;

    // External scanners (via ESP32/CC1101/nRF24)
    WifiScanner* wifi;

    // Active scanner pointers (point to whichever is in use)
    SubGhzScanner* subghz_active;
    BleScanner* ble_active;

    // State
    bool running;
    ScanSlotType current_slot;
    uint8_t subghz_frequency_index;
    uint32_t last_ble_scan;
    uint32_t last_wifi_scan;
    uint32_t start_time;

    // Thread
    FuriThread* scheduler_thread;
    FuriMutex* mutex;
    volatile bool should_stop;

    // Pause flags
    bool subghz_paused;
    bool ble_paused;
    bool wifi_paused;

    // BT serial for time-multiplexed BLE scanning
    FlockBtSerial* bt_serial;
    bool ble_scan_in_progress;

    // USB CDC for time-multiplexed IR scanning
    // IR scanning conflicts with USB CDC dual mode, so we pause/resume USB
    FlockUsbCdc* usb_cdc;
    bool ir_scan_in_progress;
    uint32_t ir_scan_start_time;
};

// ============================================================================
// Internal Callback Declarations
// ============================================================================

void scheduler_subghz_callback_impl(
    const FlockSubGhzDetection* detection,
    SubGhzSignalType signal_type,
    void* context);

void scheduler_ble_callback_impl(
    const BleDeviceExtended* device,
    void* context);

void scheduler_wifi_callback_impl(
    const WifiNetworkExtended* network,
    void* context);

void scheduler_wifi_deauth_callback_impl(
    const WifiDeauthDetection* deauth,
    void* context);

void scheduler_ir_callback_impl(
    const FlockIrDetection* detection,
    IrSignalType signal_type,
    void* context);

void scheduler_nfc_callback_impl(
    const FlockNfcDetectionExtended* detection,
    void* context);

// ============================================================================
// Internal Helper Declarations
// ============================================================================

bool should_use_internal(RadioSourceMode mode, bool external_available);
bool should_use_external(RadioSourceMode mode, bool external_available);

// Scheduler thread function
int32_t scheduler_thread_func(void* context);

// ============================================================================
// External Radio Data Callback
// ============================================================================
// Routes external radio responses to the appropriate scanner

void scheduler_external_radio_callback(
    uint8_t cmd,
    const uint8_t* data,
    size_t len,
    void* context);

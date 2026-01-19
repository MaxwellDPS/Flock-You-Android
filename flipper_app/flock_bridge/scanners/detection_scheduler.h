#pragma once

#include "../flock_bridge_app.h"
#include "../protocol/flock_protocol.h"

// ============================================================================
// Detection Scheduler
// ============================================================================
// Time-multiplexed scanner that cycles through:
// - Sub-GHz frequency hopping (continuous background)
// - BLE scanning (burst mode)
// - IR detection (passive, always on)
// - NFC detection (passive, always on)
//
// Sub-GHz runs during all "downtime" between other scans.

typedef struct DetectionScheduler DetectionScheduler;

// ============================================================================
// Scan Slot Types
// ============================================================================

typedef enum {
    ScanSlotSubGhz,
    ScanSlotBle,
    ScanSlotIr,
    ScanSlotNfc,
    ScanSlotCount,
} ScanSlotType;

// ============================================================================
// Sub-GHz Frequency Presets
// ============================================================================

typedef enum {
    SubGhzPreset315,    // 315 MHz - US garage doors, car remotes
    SubGhzPreset433,    // 433.92 MHz - EU remotes, sensors
    SubGhzPreset868,    // 868 MHz - EU devices
    SubGhzPreset915,    // 915 MHz - US ISM band
    SubGhzPresetCustom, // Custom frequency
    SubGhzPresetCount,
} SubGhzPreset;

// ============================================================================
// Configuration
// ============================================================================

#define SUBGHZ_HOP_INTERVAL_MS      500   // Time per frequency
#define BLE_SCAN_DURATION_MS        2000  // BLE burst scan duration
#define BLE_SCAN_INTERVAL_MS        5000  // Time between BLE scans
#define SCHEDULER_TICK_MS           100   // Main loop tick rate

// Sub-GHz frequency table (Hz)
static const uint32_t SUBGHZ_FREQUENCIES[] = {
    315000000,   // 315 MHz
    433920000,   // 433.92 MHz
    868350000,   // 868.35 MHz
    915000000,   // 915 MHz
    300000000,   // 300 MHz (low end)
    390000000,   // 390 MHz
    418000000,   // 418 MHz
    426000000,   // 426 MHz (TPMS)
    445000000,   // 445 MHz
    925000000,   // 925 MHz (high end)
};
#define SUBGHZ_FREQUENCY_COUNT (sizeof(SUBGHZ_FREQUENCIES) / sizeof(SUBGHZ_FREQUENCIES[0]))

// ============================================================================
// Callbacks
// ============================================================================

typedef void (*SubGhzDetectionCallback)(
    const FlockSubGhzDetection* detection,
    void* context);

typedef void (*BleDetectionCallback)(
    const FlockBleDevice* device,
    void* context);

typedef void (*IrDetectionCallback)(
    const FlockIrDetection* detection,
    void* context);

typedef void (*NfcDetectionCallback)(
    const FlockNfcDetection* detection,
    void* context);

// ============================================================================
// Scheduler Configuration
// ============================================================================

typedef struct {
    // Enable/disable scan types
    bool enable_subghz;
    bool enable_ble;
    bool enable_ir;
    bool enable_nfc;

    // Sub-GHz settings
    uint32_t subghz_hop_interval_ms;
    bool subghz_continuous;          // Run during all downtime

    // BLE settings
    uint32_t ble_scan_duration_ms;
    uint32_t ble_scan_interval_ms;
    bool ble_detect_trackers;        // Detect AirTags, Tiles, etc.

    // Callbacks
    SubGhzDetectionCallback subghz_callback;
    BleDetectionCallback ble_callback;
    IrDetectionCallback ir_callback;
    NfcDetectionCallback nfc_callback;
    void* callback_context;
} SchedulerConfig;

// ============================================================================
// Scheduler Statistics
// ============================================================================

typedef struct {
    uint32_t subghz_detections;
    uint32_t ble_devices_found;
    uint32_t ir_signals_captured;
    uint32_t nfc_tags_detected;
    uint32_t subghz_frequencies_scanned;
    uint32_t ble_scans_completed;
    uint32_t uptime_seconds;
} SchedulerStats;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate detection scheduler.
 */
DetectionScheduler* detection_scheduler_alloc(void);

/**
 * Free detection scheduler.
 */
void detection_scheduler_free(DetectionScheduler* scheduler);

/**
 * Configure the scheduler.
 */
void detection_scheduler_configure(
    DetectionScheduler* scheduler,
    const SchedulerConfig* config);

/**
 * Start all scanning.
 */
bool detection_scheduler_start(DetectionScheduler* scheduler);

/**
 * Stop all scanning.
 */
void detection_scheduler_stop(DetectionScheduler* scheduler);

/**
 * Check if scheduler is running.
 */
bool detection_scheduler_is_running(DetectionScheduler* scheduler);

/**
 * Get scheduler statistics.
 */
void detection_scheduler_get_stats(
    DetectionScheduler* scheduler,
    SchedulerStats* stats);

/**
 * Get current scan slot.
 */
ScanSlotType detection_scheduler_get_current_slot(DetectionScheduler* scheduler);

/**
 * Get current Sub-GHz frequency.
 */
uint32_t detection_scheduler_get_current_frequency(DetectionScheduler* scheduler);

/**
 * Force switch to a specific Sub-GHz frequency.
 */
void detection_scheduler_set_frequency(
    DetectionScheduler* scheduler,
    uint32_t frequency);

/**
 * Pause/resume specific scan types.
 */
void detection_scheduler_pause_subghz(DetectionScheduler* scheduler, bool pause);
void detection_scheduler_pause_ble(DetectionScheduler* scheduler, bool pause);

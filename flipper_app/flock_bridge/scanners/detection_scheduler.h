#pragma once

#include "../flock_bridge_app.h"
#include "../protocol/flock_protocol.h"
#include "../helpers/external_radio.h"

// ============================================================================
// Detection Scheduler
// ============================================================================
// Time-multiplexed scanner that cycles through:
// - Sub-GHz frequency hopping (continuous background)
// - BLE scanning (burst mode)
// - WiFi scanning (via external ESP32)
// - IR detection (passive, always on)
// - NFC detection (passive, always on)
//
// Sub-GHz runs during all "downtime" between other scans.
//
// Supports both internal Flipper radios and external modules:
// - Internal: Sub-GHz (CC1101), BLE, IR, NFC
// - External: WiFi (ESP32), Sub-GHz (CC1101), BLE (nRF24)
//
// When both internal and external radios are available for the same type,
// user settings determine which to use (only one of each type active).

typedef struct DetectionScheduler DetectionScheduler;

// ============================================================================
// Scan Slot Types
// ============================================================================

typedef enum {
    ScanSlotSubGhz,
    ScanSlotBle,
    ScanSlotWifi,
    ScanSlotIr,
    ScanSlotNfc,
    ScanSlotCount,
} ScanSlotType;

// ============================================================================
// Radio Source Selection
// ============================================================================
// Determines whether to use internal Flipper radio or external module

typedef enum {
    RadioSourceAuto,        // Use external if available, fallback to internal
    RadioSourceInternal,    // Force internal only (Flipper's built-in radios)
    RadioSourceExternal,    // Force external only (ESP32, CC1101 board, etc.)
    RadioSourceBoth,        // Use both simultaneously (where possible)
} RadioSourceMode;

// Per-radio source settings
typedef struct {
    RadioSourceMode subghz_source;  // Internal CC1101 vs external CC1101
    RadioSourceMode ble_source;     // Internal BLE vs external nRF24
    RadioSourceMode wifi_source;    // External ESP32 only (no internal WiFi)
} RadioSourceSettings;

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

// Sub-GHz hop interval - increased from 500ms to 2500ms to allow complete signal decoding
// Most RF protocols transmit in bursts of 100-500ms, with retransmissions taking longer
// 2500ms gives enough time to decode a signal including retransmissions
#define SUBGHZ_HOP_INTERVAL_MS      2500  // Time per frequency (was 500ms - too fast!)

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

typedef void (*WifiDetectionCallback)(
    const FlockWifiNetwork* network,
    void* context);

typedef void (*WifiDeauthCallback)(
    const uint8_t* bssid,
    const uint8_t* target,
    uint8_t reason,
    uint32_t count,
    void* context);

// ============================================================================
// Scheduler Configuration
// ============================================================================

typedef struct {
    // Enable/disable scan types
    bool enable_subghz;
    bool enable_ble;
    bool enable_wifi;
    bool enable_ir;
    bool enable_nfc;

    // Radio source selection
    RadioSourceSettings radio_sources;

    // Sub-GHz settings
    uint32_t subghz_hop_interval_ms;
    bool subghz_continuous;          // Run during all downtime

    // BLE settings
    uint32_t ble_scan_duration_ms;
    uint32_t ble_scan_interval_ms;
    bool ble_detect_trackers;        // Detect AirTags, Tiles, etc.

    // WiFi settings (requires external ESP32)
    uint32_t wifi_scan_interval_ms;
    uint8_t wifi_channel;            // 0 = channel hop
    bool wifi_monitor_probes;
    bool wifi_detect_deauths;

    // Callbacks
    SubGhzDetectionCallback subghz_callback;
    BleDetectionCallback ble_callback;
    WifiDetectionCallback wifi_callback;
    WifiDeauthCallback wifi_deauth_callback;
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
    uint32_t wifi_networks_found;
    uint32_t wifi_deauths_detected;
    uint32_t ir_signals_captured;
    uint32_t nfc_tags_detected;
    uint32_t subghz_frequencies_scanned;
    uint32_t ble_scans_completed;
    uint32_t wifi_scans_completed;
    uint32_t uptime_seconds;

    // Active radio sources
    bool using_internal_subghz;
    bool using_external_subghz;
    bool using_internal_ble;
    bool using_external_ble;
    bool using_external_wifi;
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
void detection_scheduler_pause_wifi(DetectionScheduler* scheduler, bool pause);

/**
 * Set external radio manager (for WiFi/external Sub-GHz/external BLE).
 * Must be called before start() if using external radios.
 */
void detection_scheduler_set_external_radio(
    DetectionScheduler* scheduler,
    ExternalRadioManager* radio_manager);

/**
 * Check if external radio is available.
 */
bool detection_scheduler_has_external_radio(DetectionScheduler* scheduler);

/**
 * Get external radio capabilities.
 */
uint32_t detection_scheduler_get_external_capabilities(DetectionScheduler* scheduler);

/**
 * Update radio source settings at runtime.
 */
void detection_scheduler_set_radio_sources(
    DetectionScheduler* scheduler,
    const RadioSourceSettings* settings);

/**
 * Get current radio source settings.
 */
void detection_scheduler_get_radio_sources(
    DetectionScheduler* scheduler,
    RadioSourceSettings* settings);

/**
 * Get radio source mode name.
 */
const char* detection_scheduler_get_source_name(RadioSourceMode mode);

/**
 * Set BT serial for time-multiplexed BLE scanning.
 * When set, BLE scanning will pause BT serial, scan, then resume.
 * This allows BLE scanning even when connected via Bluetooth.
 */
void detection_scheduler_set_bt_serial(
    DetectionScheduler* scheduler,
    struct FlockBtSerial* bt_serial);

/**
 * Check if time-multiplexed BLE scanning is available.
 * Returns true if BT serial is set and can be paused for scanning.
 */
bool detection_scheduler_can_ble_scan(DetectionScheduler* scheduler);

/**
 * Set USB CDC for time-multiplexed IR scanning.
 * When set, IR scanning will pause USB CDC, scan, then resume.
 * This allows IR scanning even when connected via USB.
 *
 * On Flipper Zero, the IR receiver uses DMA/timer resources that can
 * conflict with USB CDC dual mode. By pausing USB CDC temporarily,
 * we can run IR scanning in burst mode.
 */
void detection_scheduler_set_usb_cdc(
    DetectionScheduler* scheduler,
    struct FlockUsbCdc* usb_cdc);

/**
 * Check if time-multiplexed IR scanning is available.
 * Returns true if USB CDC is set (IR will use burst mode) or
 * if no USB CDC is active (IR can run continuously).
 */
bool detection_scheduler_can_ir_scan(DetectionScheduler* scheduler);

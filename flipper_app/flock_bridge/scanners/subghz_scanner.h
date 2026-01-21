#pragma once

#include "../protocol/flock_protocol.h"
#include <furi.h>
#include <furi_hal.h>

// ============================================================================
// Sub-GHz Scanner
// ============================================================================
// Handles Sub-GHz radio scanning with:
// - Frequency hopping across common bands
// - Signal detection and characterization
// - Protocol identification for known protocols
// - Replay attack detection
// - Jamming detection

typedef struct SubGhzScanner SubGhzScanner;

// ============================================================================
// Detection Types
// ============================================================================

typedef enum {
    SubGhzSignalUnknown,
    SubGhzSignalRemote,       // Car/garage remote
    SubGhzSignalSensor,       // Weather sensor, TPMS
    SubGhzSignalPager,        // Pager signal
    SubGhzSignalReplay,       // Detected replay attack
    SubGhzSignalJamming,      // Detected jamming
} SubGhzSignalType;

// ============================================================================
// Known Protocol IDs
// ============================================================================

typedef enum {
    SubGhzProtoUnknown = 0,
    SubGhzProtoKeeloq,
    SubGhzProtoPrinceton,
    SubGhzProtoNiceFlo,
    SubGhzProtoNiceFlorS,
    SubGhzProtoCame,
    SubGhzProtoCameTwee,
    SubGhzProtoFaacSlh,
    SubGhzProtoGateTx,
    SubGhzProtoHormann,
    SubGhzProtoLinear,
    SubGhzProtoMegacode,
    SubGhzProtoSecuritPlus,
    SubGhzProtoHoltek,
    SubGhzProtoChamberlain,
    SubGhzProtoTPMS,
    SubGhzProtoOregon,
    SubGhzProtoAcurite,
    SubGhzProtoLaCrosse,
} SubGhzProtocolId;

// ============================================================================
// Modulation Presets
// ============================================================================

typedef enum {
    SubGhzPresetOok650 = 0,   // OOK, 650kHz bandwidth - most common
    SubGhzPresetOok270,       // OOK, 270kHz bandwidth - narrowband
    SubGhzPreset2FSKDev238,   // 2-FSK, 2.38kHz deviation - sensors
    SubGhzPreset2FSKDev476,   // 2-FSK, 4.76kHz deviation - keyfobs
    SubGhzPresetCount
} SubGhzPresetType;

// ============================================================================
// Callback
// ============================================================================

typedef void (*SubGhzScanCallback)(
    const FlockSubGhzDetection* detection,
    SubGhzSignalType signal_type,
    void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    bool detect_replays;
    bool detect_jamming;
    int8_t rssi_threshold;        // Minimum RSSI to report (default: -90 dBm)
    uint32_t min_signal_duration; // Minimum signal duration in us
    SubGhzScanCallback callback;
    void* callback_context;
} SubGhzScannerConfig;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate Sub-GHz scanner.
 */
SubGhzScanner* subghz_scanner_alloc(void);

/**
 * Free Sub-GHz scanner.
 */
void subghz_scanner_free(SubGhzScanner* scanner);

/**
 * Configure the scanner.
 */
void subghz_scanner_configure(
    SubGhzScanner* scanner,
    const SubGhzScannerConfig* config);

/**
 * Start scanning at a specific frequency.
 */
bool subghz_scanner_start(SubGhzScanner* scanner, uint32_t frequency);

/**
 * Stop scanning.
 */
void subghz_scanner_stop(SubGhzScanner* scanner);

/**
 * Switch to a different frequency (while running).
 */
bool subghz_scanner_set_frequency(SubGhzScanner* scanner, uint32_t frequency);

/**
 * Get current frequency.
 */
uint32_t subghz_scanner_get_frequency(SubGhzScanner* scanner);

/**
 * Check if scanner is running.
 */
bool subghz_scanner_is_running(SubGhzScanner* scanner);

/**
 * Get current RSSI at the tuned frequency.
 */
int8_t subghz_scanner_get_rssi(SubGhzScanner* scanner);

/**
 * Get detection count.
 */
uint32_t subghz_scanner_get_detection_count(SubGhzScanner* scanner);

/**
 * Get protocol name from ID.
 */
const char* subghz_scanner_get_protocol_name(SubGhzProtocolId proto_id);

/**
 * Reset decoder state to clear accumulated buffers.
 * Call periodically to prevent memory growth from RF noise.
 */
void subghz_scanner_reset_decoder(SubGhzScanner* scanner);

/**
 * Recreate the receiver to completely free all internal memory.
 * This is more aggressive than reset - frees and reallocates the receiver.
 * Use for periodic memory cleanup during long-running sessions.
 */
void subghz_scanner_recreate_receiver(SubGhzScanner* scanner);

// ============================================================================
// Preset Management
// ============================================================================

/**
 * Set the modulation preset.
 * Different presets detect different signal types (OOK vs FSK).
 * Returns false if decode is in progress and preset change was deferred.
 */
bool subghz_scanner_set_preset(SubGhzScanner* scanner, SubGhzPresetType preset);

/**
 * Get current preset.
 */
SubGhzPresetType subghz_scanner_get_preset(SubGhzScanner* scanner);

/**
 * Cycle to the next preset (for automatic modulation coverage).
 */
void subghz_scanner_cycle_preset(SubGhzScanner* scanner);

// ============================================================================
// Decode State
// ============================================================================

/**
 * Check if decoder is actively processing a signal.
 * Returns true if decode is in progress or within cooldown period.
 * Use this to defer frequency hopping during active reception.
 */
bool subghz_decoder_is_active(SubGhzScanner* scanner);

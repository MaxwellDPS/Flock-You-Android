#pragma once

/**
 * Internal header for Sub-GHz scanner module.
 * Contains shared structures and declarations between subghz_scanner.c and subghz_decoder.c.
 * NOT part of the public API - do not include in external code.
 */

#include "subghz_scanner.h"
#include <lib/subghz/subghz_tx_rx_worker.h>
#include <lib/subghz/receiver.h>
#include <lib/subghz/transmitter.h>
#include <lib/subghz/subghz_setting.h>
#include <lib/subghz/subghz_protocol_registry.h>
#include <lib/subghz/devices/devices.h>
#include <string.h>
#include <stdlib.h>

#define TAG "SubGhzScanner"
#define SUBGHZ_DEVICE_CC1101_INT_NAME "cc1101_int"

// ============================================================================
// Timing Constants
// ============================================================================

// Minimum time to stay on a frequency before hopping (allows complete decoding)
// Most protocols need 100-500ms to decode, give 2500ms margin for repeated transmissions
#define SUBGHZ_MIN_DECODE_TIME_MS 2500

// Time after last pulse activity to consider decoding complete
// Protects against mid-decode frequency switches
#define SUBGHZ_DECODE_COOLDOWN_MS 300

// ============================================================================
// Replay Detection Constants
// ============================================================================

#define MAX_SIGNAL_HISTORY 32
#define REPLAY_WINDOW_MS   60000  // 1 minute window for replay detection

// ============================================================================
// Signal History Entry
// ============================================================================

typedef struct {
    uint32_t frequency;
    uint32_t hash;           // Simple hash of signal characteristics
    uint32_t timestamp;
    uint8_t count;           // Times seen
    bool valid;
} SignalHistoryEntry;

// ============================================================================
// Scanner Structure
// ============================================================================
// Note: SubGhzPresetType enum is defined in subghz_scanner.h (public header)

struct SubGhzScanner {
    SubGhzScannerConfig config;

    // Hardware
    const SubGhzDevice* device;
    SubGhzEnvironment* environment;
    SubGhzReceiver* receiver;
    SubGhzSetting* setting;

    // State
    bool running;
    bool device_begun;  // Track if subghz_devices_begin() was called
    uint32_t current_frequency;
    uint32_t detection_count;

    // Preset cycling
    SubGhzPresetType current_preset;
    bool multi_preset_mode;           // Cycle through presets automatically

    // Decode protection - prevent frequency hop during active decode
    uint32_t last_pulse_time;         // Last time we received a pulse
    bool decode_in_progress;          // Flag indicating active decoding
    uint32_t decode_start_time;       // When decoding started

    // Protocol registry status
    bool protocol_registry_loaded;
    bool settings_loaded;

    // Replay detection
    SignalHistoryEntry signal_history[MAX_SIGNAL_HISTORY];
    uint8_t history_head;

    // Jamming detection
    int8_t rssi_baseline;
    uint32_t high_rssi_start;
    bool jamming_detected;

    // Thread
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool should_stop;
};

// ============================================================================
// Decoder Functions (subghz_decoder.c)
// ============================================================================

/**
 * Get protocol name from ID.
 */
const char* subghz_decoder_get_protocol_name(SubGhzProtocolId proto_id);

/**
 * Identify protocol ID from protocol name string.
 */
SubGhzProtocolId subghz_decoder_identify_protocol(const char* name);

/**
 * Compute a hash for signal characteristics (for replay detection).
 */
uint32_t subghz_decoder_compute_signal_hash(uint32_t frequency, uint8_t modulation, uint16_t duration);

/**
 * Check if a signal appears to be a replay attack.
 * Returns true if replay detected.
 */
bool subghz_decoder_check_replay_attack(SubGhzScanner* scanner, uint32_t hash);

/**
 * Check for jamming based on RSSI.
 * Will invoke callback if jamming is detected.
 */
void subghz_decoder_check_jamming(SubGhzScanner* scanner, int8_t rssi);

/**
 * Receiver callback - called when protocol is decoded.
 */
void subghz_decoder_receiver_callback(
    SubGhzReceiver* receiver,
    SubGhzProtocolDecoderBase* decoder_base,
    void* context);

/**
 * Capture callback - receives raw pulse data from radio.
 */
void subghz_decoder_capture_callback(bool level, uint32_t duration, void* context);

/**
 * Mark decode as complete (called after successful decode callback).
 */
void subghz_decoder_mark_complete(SubGhzScanner* scanner);

/**
 * Get FuriHalSubGhzPreset enum value for our preset type.
 */
FuriHalSubGhzPreset subghz_get_furi_preset(SubGhzPresetType preset);

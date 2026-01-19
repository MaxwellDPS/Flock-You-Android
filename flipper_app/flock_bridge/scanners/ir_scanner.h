#pragma once

#include "../protocol/flock_protocol.h"
#include <furi.h>
#include <furi_hal.h>

// ============================================================================
// IR Scanner (Passive)
// ============================================================================
// Passive IR receiver that detects and decodes infrared signals.
// Can run continuously alongside other scanners.
// Detects:
// - Remote control signals (TV, AC, etc.)
// - IR communication
// - Potential IR-based attacks (brute force, replay)

typedef struct IrScanner IrScanner;

// ============================================================================
// IR Protocol IDs
// ============================================================================

typedef enum {
    IrProtoUnknown = 0,
    IrProtoNEC,
    IrProtoNECext,
    IrProtoSamsung32,
    IrProtoRC5,
    IrProtoRC5X,
    IrProtoRC6,
    IrProtoSIRC,
    IrProtoSIRC15,
    IrProtoSIRC20,
    IrProtoKaseikyo,
    IrProtoRCA,
} IrProtocolId;

// ============================================================================
// Detection Types
// ============================================================================

typedef enum {
    IrSignalNormal,
    IrSignalRepeat,        // Repeated signal (button held)
    IrSignalBruteForce,    // Rapid different codes (attack)
    IrSignalReplay,        // Same code repeated suspiciously
} IrSignalType;

// ============================================================================
// Callback
// ============================================================================

typedef void (*IrScanCallback)(
    const FlockIrDetection* detection,
    IrSignalType signal_type,
    void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    bool detect_brute_force;
    bool detect_replay;
    uint32_t brute_force_threshold;  // Codes per second threshold
    uint32_t replay_window_ms;       // Window for replay detection

    IrScanCallback callback;
    void* callback_context;
} IrScannerConfig;

// ============================================================================
// Statistics
// ============================================================================

typedef struct {
    uint32_t total_signals;
    uint32_t unique_commands;
    uint32_t brute_force_detected;
    uint32_t replay_detected;
} IrScannerStats;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate IR scanner.
 */
IrScanner* ir_scanner_alloc(void);

/**
 * Free IR scanner.
 */
void ir_scanner_free(IrScanner* scanner);

/**
 * Configure the scanner.
 */
void ir_scanner_configure(
    IrScanner* scanner,
    const IrScannerConfig* config);

/**
 * Start passive IR reception.
 */
bool ir_scanner_start(IrScanner* scanner);

/**
 * Stop IR reception.
 */
void ir_scanner_stop(IrScanner* scanner);

/**
 * Check if scanner is running.
 */
bool ir_scanner_is_running(IrScanner* scanner);

/**
 * Get scanner statistics.
 */
void ir_scanner_get_stats(IrScanner* scanner, IrScannerStats* stats);

/**
 * Get protocol name from ID.
 */
const char* ir_scanner_get_protocol_name(IrProtocolId proto_id);

#include "ir_scanner.h"
#include <infrared.h>
#include <infrared_worker.h>
#include <string.h>
#include <stdlib.h>

#define TAG "IrScanner"

// ============================================================================
// Brute Force / Replay Detection
// ============================================================================

#define MAX_COMMAND_HISTORY 32
#define BRUTE_FORCE_WINDOW_MS 1000  // 1 second window

typedef struct {
    uint32_t address;
    uint32_t command;
    IrProtocolId protocol;
    uint32_t timestamp;
    bool valid;
} CommandHistoryEntry;

// ============================================================================
// Scanner Structure
// ============================================================================

struct IrScanner {
    IrScannerConfig config;
    IrScannerStats stats;

    // IR Worker
    InfraredWorker* worker;

    // State
    bool running;

    // Command history for attack detection
    CommandHistoryEntry command_history[MAX_COMMAND_HISTORY];
    uint8_t history_head;
    uint32_t commands_this_second;
    uint32_t last_second_start;

    // Sync
    FuriMutex* mutex;
};

// ============================================================================
// Protocol Names
// ============================================================================

static const char* PROTOCOL_NAMES[] = {
    [IrProtoUnknown] = "Unknown",
    [IrProtoNEC] = "NEC",
    [IrProtoNECext] = "NECext",
    [IrProtoSamsung32] = "Samsung32",
    [IrProtoRC5] = "RC5",
    [IrProtoRC5X] = "RC5X",
    [IrProtoRC6] = "RC6",
    [IrProtoSIRC] = "SIRC",
    [IrProtoSIRC15] = "SIRC15",
    [IrProtoSIRC20] = "SIRC20",
    [IrProtoKaseikyo] = "Kaseikyo",
    [IrProtoRCA] = "RCA",
};

const char* ir_scanner_get_protocol_name(IrProtocolId proto_id) {
    if (proto_id < sizeof(PROTOCOL_NAMES) / sizeof(PROTOCOL_NAMES[0])) {
        return PROTOCOL_NAMES[proto_id];
    }
    return "Unknown";
}

// ============================================================================
// Helper Functions
// ============================================================================

static IrProtocolId map_infrared_protocol(InfraredProtocol protocol) {
    switch (protocol) {
    case InfraredProtocolNEC: return IrProtoNEC;
    case InfraredProtocolNECext: return IrProtoNECext;
    case InfraredProtocolSamsung32: return IrProtoSamsung32;
    case InfraredProtocolRC5: return IrProtoRC5;
    case InfraredProtocolRC5X: return IrProtoRC5X;
    case InfraredProtocolRC6: return IrProtoRC6;
    case InfraredProtocolSIRC: return IrProtoSIRC;
    case InfraredProtocolSIRC15: return IrProtoSIRC15;
    case InfraredProtocolSIRC20: return IrProtoSIRC20;
    case InfraredProtocolKaseikyo: return IrProtoKaseikyo;
    case InfraredProtocolRCA: return IrProtoRCA;
    default: return IrProtoUnknown;
    }
}

static bool check_brute_force(IrScanner* scanner) {
    if (!scanner->config.detect_brute_force) return false;

    uint32_t now = furi_get_tick();

    // Reset counter every second
    if ((now - scanner->last_second_start) >= BRUTE_FORCE_WINDOW_MS) {
        scanner->last_second_start = now;
        scanner->commands_this_second = 0;
    }

    scanner->commands_this_second++;

    // Check if exceeds threshold
    if (scanner->commands_this_second >= scanner->config.brute_force_threshold) {
        scanner->stats.brute_force_detected++;
        return true;
    }

    return false;
}

static bool check_replay(IrScanner* scanner, uint32_t address, uint32_t command, IrProtocolId protocol) {
    if (!scanner->config.detect_replay) return false;

    uint32_t now = furi_get_tick();
    uint32_t window = scanner->config.replay_window_ms;
    uint8_t same_command_count = 0;

    // Count occurrences of this exact command in recent history
    for (int i = 0; i < MAX_COMMAND_HISTORY; i++) {
        CommandHistoryEntry* entry = &scanner->command_history[i];
        if (entry->valid &&
            entry->address == address &&
            entry->command == command &&
            entry->protocol == protocol &&
            (now - entry->timestamp) < window) {
            same_command_count++;
        }
    }

    // Add to history
    CommandHistoryEntry* entry = &scanner->command_history[scanner->history_head];
    entry->address = address;
    entry->command = command;
    entry->protocol = protocol;
    entry->timestamp = now;
    entry->valid = true;
    scanner->history_head = (scanner->history_head + 1) % MAX_COMMAND_HISTORY;

    // More than 5 identical commands in window = suspicious
    if (same_command_count >= 5) {
        scanner->stats.replay_detected++;
        return true;
    }

    return false;
}

// ============================================================================
// IR Worker Callback
// ============================================================================

static void ir_worker_rx_callback(void* context, InfraredWorkerSignal* signal) {
    IrScanner* scanner = context;
    if (!scanner || !scanner->running) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Get signal info
    const InfraredMessage* message = infrared_worker_get_decoded_signal(signal);

    if (message) {
        // Create detection
        FlockIrDetection detection = {
            .timestamp = furi_get_tick() / 1000,
            .protocol_id = map_infrared_protocol(message->protocol),
            .address = message->address,
            .command = message->command,
            .repeat = message->repeat,
            .signal_strength = 0,  // IR doesn't have RSSI
        };

        strncpy(detection.protocol_name,
            infrared_get_protocol_name(message->protocol),
            sizeof(detection.protocol_name) - 1);

        // Determine signal type
        IrSignalType signal_type = IrSignalNormal;

        if (message->repeat) {
            signal_type = IrSignalRepeat;
        } else if (check_brute_force(scanner)) {
            signal_type = IrSignalBruteForce;
            FURI_LOG_W(TAG, "IR brute force detected!");
        } else if (check_replay(scanner, message->address, message->command, detection.protocol_id)) {
            signal_type = IrSignalReplay;
            FURI_LOG_W(TAG, "IR replay detected!");
        }

        scanner->stats.total_signals++;
        if (!message->repeat) {
            scanner->stats.unique_commands++;
        }

        // Invoke callback
        if (scanner->config.callback) {
            scanner->config.callback(&detection, signal_type, scanner->config.callback_context);
        }

        FURI_LOG_D(TAG, "IR: %s addr=0x%08lX cmd=0x%08lX %s",
            detection.protocol_name,
            (unsigned long)detection.address,
            (unsigned long)detection.command,
            message->repeat ? "(repeat)" : "");
    } else {
        // Raw signal (unknown protocol)
        // Could capture raw timings for analysis
        FURI_LOG_D(TAG, "IR: Unknown protocol (raw signal)");
    }

    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// Public API
// ============================================================================

IrScanner* ir_scanner_alloc(void) {
    IrScanner* scanner = malloc(sizeof(IrScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(IrScanner));

    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.detect_brute_force = true;
    scanner->config.detect_replay = true;
    scanner->config.brute_force_threshold = 20;  // 20 codes/second
    scanner->config.replay_window_ms = 5000;     // 5 second window

    // Create IR worker
    scanner->worker = infrared_worker_alloc();

    FURI_LOG_I(TAG, "IR scanner allocated");
    return scanner;
}

void ir_scanner_free(IrScanner* scanner) {
    if (!scanner) return;

    ir_scanner_stop(scanner);

    if (scanner->worker) {
        infrared_worker_free(scanner->worker);
    }
    if (scanner->mutex) {
        furi_mutex_free(scanner->mutex);
    }

    free(scanner);
    FURI_LOG_I(TAG, "IR scanner freed");
}

void ir_scanner_configure(IrScanner* scanner, const IrScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(IrScannerConfig));
    furi_mutex_release(scanner->mutex);
}

bool ir_scanner_start(IrScanner* scanner) {
    if (!scanner || scanner->running) return false;

    FURI_LOG_I(TAG, "Starting IR scanner");

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Set callback
    infrared_worker_rx_set_received_signal_callback(
        scanner->worker, ir_worker_rx_callback, scanner);

    // Enable all protocols
    infrared_worker_rx_enable_blink_on_receiving(scanner->worker, false);

    // Start receiving
    infrared_worker_rx_start(scanner->worker);

    scanner->running = true;
    scanner->last_second_start = furi_get_tick();

    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "IR scanner started");
    return true;
}

void ir_scanner_stop(IrScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping IR scanner");

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    infrared_worker_rx_stop(scanner->worker);
    scanner->running = false;

    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "IR scanner stopped");
}

bool ir_scanner_is_running(IrScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

void ir_scanner_get_stats(IrScanner* scanner, IrScannerStats* stats) {
    if (!scanner || !stats) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(stats, &scanner->stats, sizeof(IrScannerStats));
    furi_mutex_release(scanner->mutex);
}

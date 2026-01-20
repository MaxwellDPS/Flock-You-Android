#include "subghz_scanner.h"
#include <lib/subghz/subghz_tx_rx_worker.h>
#include <lib/subghz/receiver.h>
#include <lib/subghz/transmitter.h>
#include <lib/subghz/subghz_setting.h>
// Note: protocol_items.h has been removed in newer SDK
// #include <lib/subghz/protocols/protocol_items.h>
#include <string.h>
#include <stdlib.h>

#define TAG "SubGhzScanner"

// ============================================================================
// Replay Detection
// ============================================================================

#define MAX_SIGNAL_HISTORY 32
#define REPLAY_WINDOW_MS   60000  // 1 minute window for replay detection

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

struct SubGhzScanner {
    SubGhzScannerConfig config;

    // Hardware
    SubGhzEnvironment* environment;
    SubGhzReceiver* receiver;
    SubGhzSetting* setting;

    // State
    bool running;
    uint32_t current_frequency;
    uint32_t detection_count;

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
// Protocol Name Table
// ============================================================================

static const char* PROTOCOL_NAMES[] = {
    [SubGhzProtoUnknown] = "Unknown",
    [SubGhzProtoKeeloq] = "KeeLoq",
    [SubGhzProtoPrinceton] = "Princeton",
    [SubGhzProtoNiceFlo] = "Nice Flo",
    [SubGhzProtoNiceFlorS] = "Nice FlorS",
    [SubGhzProtoCame] = "CAME",
    [SubGhzProtoCameTwee] = "CAME Twee",
    [SubGhzProtoFaacSlh] = "FAAC SLH",
    [SubGhzProtoGateTx] = "GateTX",
    [SubGhzProtoHormann] = "Hormann",
    [SubGhzProtoLinear] = "Linear",
    [SubGhzProtoMegacode] = "Megacode",
    [SubGhzProtoSecuritPlus] = "Security+",
    [SubGhzProtoHoltek] = "Holtek",
    [SubGhzProtoChamberlain] = "Chamberlain",
    [SubGhzProtoTPMS] = "TPMS",
    [SubGhzProtoOregon] = "Oregon",
    [SubGhzProtoAcurite] = "Acurite",
    [SubGhzProtoLaCrosse] = "LaCrosse",
};

const char* subghz_scanner_get_protocol_name(SubGhzProtocolId proto_id) {
    if (proto_id < sizeof(PROTOCOL_NAMES) / sizeof(PROTOCOL_NAMES[0])) {
        return PROTOCOL_NAMES[proto_id];
    }
    return "Unknown";
}

// ============================================================================
// Helper Functions
// ============================================================================

static uint32_t compute_signal_hash(uint32_t frequency, uint8_t modulation, uint16_t duration) {
    // Simple hash combining signal characteristics
    return (frequency / 1000) ^ ((uint32_t)modulation << 16) ^ ((uint32_t)duration << 8);
}

static SubGhzProtocolId identify_protocol(const char* name) {
    if (!name) return SubGhzProtoUnknown;

    // Map protocol names to IDs
    if (strstr(name, "KeeLoq")) return SubGhzProtoKeeloq;
    if (strstr(name, "Princeton")) return SubGhzProtoPrinceton;
    if (strstr(name, "Nice Flo")) return SubGhzProtoNiceFlo;
    if (strstr(name, "CAME")) return SubGhzProtoCame;
    if (strstr(name, "Hormann")) return SubGhzProtoHormann;
    if (strstr(name, "Linear")) return SubGhzProtoLinear;
    if (strstr(name, "TPMS")) return SubGhzProtoTPMS;
    if (strstr(name, "Oregon")) return SubGhzProtoOregon;
    if (strstr(name, "Acurite")) return SubGhzProtoAcurite;
    if (strstr(name, "LaCrosse")) return SubGhzProtoLaCrosse;

    return SubGhzProtoUnknown;
}

static bool check_replay_attack(SubGhzScanner* scanner, uint32_t hash) {
    uint32_t now = furi_get_tick();

    for (int i = 0; i < MAX_SIGNAL_HISTORY; i++) {
        SignalHistoryEntry* entry = &scanner->signal_history[i];
        if (entry->valid && entry->hash == hash) {
            // Check if within replay window
            if ((now - entry->timestamp) < REPLAY_WINDOW_MS) {
                entry->count++;
                entry->timestamp = now;

                // If seen 3+ times in short window, likely replay
                if (entry->count >= 3) {
                    return true;
                }
            }
            return false;
        }
    }

    // Add to history
    SignalHistoryEntry* entry = &scanner->signal_history[scanner->history_head];
    entry->frequency = scanner->current_frequency;
    entry->hash = hash;
    entry->timestamp = now;
    entry->count = 1;
    entry->valid = true;

    scanner->history_head = (scanner->history_head + 1) % MAX_SIGNAL_HISTORY;
    return false;
}

static void check_jamming(SubGhzScanner* scanner, int8_t rssi) {
    if (!scanner->config.detect_jamming) return;

    // Jamming = sustained high RSSI without valid signals
    if (rssi > -50) {  // Very strong signal
        if (scanner->high_rssi_start == 0) {
            scanner->high_rssi_start = furi_get_tick();
        } else if ((furi_get_tick() - scanner->high_rssi_start) > 1000) {
            // High RSSI for > 1 second without valid decode = jamming
            if (!scanner->jamming_detected) {
                scanner->jamming_detected = true;
                FURI_LOG_W(TAG, "Jamming detected at %lu Hz", scanner->current_frequency);

                // Create jamming detection
                if (scanner->config.callback) {
                    FlockSubGhzDetection detection = {
                        .frequency = scanner->current_frequency,
                        .rssi = rssi,
                        .modulation = ModulationUnknown,
                        .duration_ms = (uint16_t)((furi_get_tick() - scanner->high_rssi_start)),
                        .bandwidth = 0,
                        .protocol_id = 0,
                    };
                    strncpy(detection.protocol_name, "JAMMING", sizeof(detection.protocol_name) - 1);

                    scanner->config.callback(&detection, SubGhzSignalJamming,
                        scanner->config.callback_context);
                }
            }
        }
    } else {
        scanner->high_rssi_start = 0;
        scanner->jamming_detected = false;
    }
}

// ============================================================================
// Receiver Callback
// ============================================================================

// Note: SubGhz receiver callback API has changed in newer firmware
// The function signature and available functions have changed
__attribute__((unused))
static void subghz_receiver_callback(SubGhzReceiver* receiver, SubGhzProtocolDecoderBase* decoder_base, void* context) {
    UNUSED(receiver);
    UNUSED(decoder_base);
    SubGhzScanner* scanner = context;
    if (!scanner || !scanner->running) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Get protocol info
    FuriString* protocol_name = furi_string_alloc();
    FuriString* protocol_data = furi_string_alloc();

    subghz_protocol_decoder_base_get_string(decoder_base, protocol_data);

    // Note: subghz_protocol_decoder_base_get_name no longer exists
    const char* name = "Unknown";

    // Get signal characteristics
    int8_t rssi = subghz_scanner_get_rssi(scanner);

    // Create detection
    FlockSubGhzDetection detection = {
        .frequency = scanner->current_frequency,
        .rssi = rssi,
        .modulation = ModulationUnknown,  // Would need deeper access to determine
        .duration_ms = 0,
        .bandwidth = 0,
        .protocol_id = identify_protocol(name),
    };

    if (name) {
        strncpy(detection.protocol_name, name, sizeof(detection.protocol_name) - 1);
    }

    // Check for replay attack
    uint32_t hash = compute_signal_hash(scanner->current_frequency, detection.modulation, 0);
    SubGhzSignalType signal_type = SubGhzSignalUnknown;

    if (scanner->config.detect_replays && check_replay_attack(scanner, hash)) {
        signal_type = SubGhzSignalReplay;
        strncpy(detection.protocol_name, "REPLAY", sizeof(detection.protocol_name) - 1);
        FURI_LOG_W(TAG, "Replay attack detected!");
    } else {
        // Categorize signal type
        switch (detection.protocol_id) {
        case SubGhzProtoKeeloq:
        case SubGhzProtoPrinceton:
        case SubGhzProtoNiceFlo:
        case SubGhzProtoCame:
        case SubGhzProtoHormann:
        case SubGhzProtoLinear:
        case SubGhzProtoChamberlain:
            signal_type = SubGhzSignalRemote;
            break;
        case SubGhzProtoTPMS:
        case SubGhzProtoOregon:
        case SubGhzProtoAcurite:
        case SubGhzProtoLaCrosse:
            signal_type = SubGhzSignalSensor;
            break;
        default:
            signal_type = SubGhzSignalUnknown;
            break;
        }
    }

    scanner->detection_count++;

    // Invoke callback
    if (scanner->config.callback) {
        scanner->config.callback(&detection, signal_type, scanner->config.callback_context);
    }

    FURI_LOG_I(TAG, "Detection: %s @ %lu Hz (RSSI: %d)",
        detection.protocol_name, detection.frequency, detection.rssi);

    furi_string_free(protocol_name);
    furi_string_free(protocol_data);

    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// Worker Thread
// ============================================================================

static int32_t subghz_scanner_worker(void* context) {
    SubGhzScanner* scanner = context;

    FURI_LOG_I(TAG, "Sub-GHz worker started at %lu Hz", scanner->current_frequency);

    while (!scanner->should_stop) {
        // Check RSSI for jamming detection
        int8_t rssi = subghz_scanner_get_rssi(scanner);
        check_jamming(scanner, rssi);

        // The receiver handles detection via callback
        // We just need to keep the thread alive and check for stop

        furi_delay_ms(50);
    }

    FURI_LOG_I(TAG, "Sub-GHz worker stopped");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

SubGhzScanner* subghz_scanner_alloc(void) {
    SubGhzScanner* scanner = malloc(sizeof(SubGhzScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(SubGhzScanner));

    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.rssi_threshold = -90;
    scanner->config.detect_replays = true;
    scanner->config.detect_jamming = true;
    scanner->config.min_signal_duration = 100;

    // Initialize Sub-GHz environment
    scanner->environment = subghz_environment_alloc();
    // Note: subghz_protocol_registry reference has changed in newer SDK
    // The protocol registry initialization needs to be updated for new API

    // Load settings
    scanner->setting = subghz_setting_alloc();
    subghz_setting_load(scanner->setting, EXT_PATH("subghz/assets/setting_user"));

    // Create receiver
    scanner->receiver = subghz_receiver_alloc_init(scanner->environment);
    subghz_receiver_set_filter(scanner->receiver, SubGhzProtocolFlag_Decodable);
    subghz_receiver_set_rx_callback(scanner->receiver, subghz_receiver_callback, scanner);

    FURI_LOG_I(TAG, "Sub-GHz scanner allocated");
    return scanner;
}

void subghz_scanner_free(SubGhzScanner* scanner) {
    if (!scanner) return;

    subghz_scanner_stop(scanner);

    if (scanner->receiver) {
        subghz_receiver_free(scanner->receiver);
    }
    if (scanner->setting) {
        subghz_setting_free(scanner->setting);
    }
    if (scanner->environment) {
        subghz_environment_free(scanner->environment);
    }
    if (scanner->mutex) {
        furi_mutex_free(scanner->mutex);
    }

    free(scanner);
    FURI_LOG_I(TAG, "Sub-GHz scanner freed");
}

void subghz_scanner_configure(SubGhzScanner* scanner, const SubGhzScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(SubGhzScannerConfig));
    furi_mutex_release(scanner->mutex);
}

bool subghz_scanner_start(SubGhzScanner* scanner, uint32_t frequency) {
    if (!scanner || scanner->running) return false;

    FURI_LOG_I(TAG, "Starting Sub-GHz scanner at %lu Hz", frequency);

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->current_frequency = frequency;

    // Configure and start radio
    furi_hal_subghz_reset();
    furi_hal_subghz_idle();

    // Note: Preset loading API has changed in newer SDK
    // furi_hal_subghz_load_custom_preset(subghz_device_cc1101_preset_ook_650khz_async_regs);
    FURI_LOG_W(TAG, "SubGhz preset loading requires updated API");

    if (!furi_hal_subghz_is_frequency_valid(frequency)) {
        FURI_LOG_E(TAG, "Invalid frequency: %lu Hz", frequency);
        furi_mutex_release(scanner->mutex);
        return false;
    }

    furi_hal_subghz_set_frequency_and_path(frequency);

    // Reset receiver
    subghz_receiver_reset(scanner->receiver);

    // Note: furi_hal_subghz_start_async_rx callback signature has changed
    // The new signature is (FuriHalSubGhzCaptureCallback callback, void* context)
    // where callback is void (*)(bool level, uint32_t duration, void* context)
    // This needs to be updated to use the new capture callback API
    FURI_LOG_W(TAG, "SubGhz async RX requires updated API");

    scanner->running = true;
    scanner->should_stop = false;

    // Start worker thread
    scanner->worker_thread = furi_thread_alloc_ex(
        "SubGhzScanWorker", 2048, subghz_scanner_worker, scanner);
    furi_thread_start(scanner->worker_thread);

    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "Sub-GHz scanner started");
    return true;
}

void subghz_scanner_stop(SubGhzScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping Sub-GHz scanner");

    scanner->should_stop = true;

    // Stop worker thread
    if (scanner->worker_thread) {
        furi_thread_join(scanner->worker_thread);
        furi_thread_free(scanner->worker_thread);
        scanner->worker_thread = NULL;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Stop radio
    furi_hal_subghz_stop_async_rx();
    furi_hal_subghz_idle();
    furi_hal_subghz_sleep();

    scanner->running = false;

    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "Sub-GHz scanner stopped");
}

bool subghz_scanner_set_frequency(SubGhzScanner* scanner, uint32_t frequency) {
    if (!scanner) return false;

    if (!furi_hal_subghz_is_frequency_valid(frequency)) {
        FURI_LOG_E(TAG, "Invalid frequency: %lu Hz", frequency);
        return false;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    if (scanner->running) {
        // Stop RX, switch frequency, restart
        furi_hal_subghz_stop_async_rx();
        furi_hal_subghz_idle();

        scanner->current_frequency = frequency;
        furi_hal_subghz_set_frequency_and_path(frequency);

        subghz_receiver_reset(scanner->receiver);
        // Note: furi_hal_subghz_start_async_rx callback signature has changed
        // furi_hal_subghz_start_async_rx(subghz_receiver_decode, scanner->receiver);
        FURI_LOG_W(TAG, "SubGhz async RX restart requires updated API");
    } else {
        scanner->current_frequency = frequency;
    }

    furi_mutex_release(scanner->mutex);

    FURI_LOG_D(TAG, "Frequency set to %lu Hz", frequency);
    return true;
}

uint32_t subghz_scanner_get_frequency(SubGhzScanner* scanner) {
    if (!scanner) return 0;
    return scanner->current_frequency;
}

bool subghz_scanner_is_running(SubGhzScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

int8_t subghz_scanner_get_rssi(SubGhzScanner* scanner) {
    if (!scanner || !scanner->running) return -128;
    return furi_hal_subghz_get_rssi();
}

uint32_t subghz_scanner_get_detection_count(SubGhzScanner* scanner) {
    if (!scanner) return 0;
    return scanner->detection_count;
}

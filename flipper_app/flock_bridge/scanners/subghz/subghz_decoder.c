/**
 * Sub-GHz Protocol Decoder
 *
 * Handles protocol decoding and signal analysis for the Sub-GHz scanner:
 * - Protocol identification and name mapping
 * - Replay attack detection via signal history
 * - Jamming detection based on RSSI patterns
 * - Raw pulse data processing
 */

#include "subghz_internal.h"

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

// ============================================================================
// Protocol Identification
// ============================================================================

const char* subghz_decoder_get_protocol_name(SubGhzProtocolId proto_id) {
    if (proto_id < sizeof(PROTOCOL_NAMES) / sizeof(PROTOCOL_NAMES[0])) {
        return PROTOCOL_NAMES[proto_id];
    }
    return "Unknown";
}

SubGhzProtocolId subghz_decoder_identify_protocol(const char* name) {
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

// ============================================================================
// Signal Analysis Helpers
// ============================================================================

uint32_t subghz_decoder_compute_signal_hash(uint32_t frequency, uint8_t modulation, uint16_t duration) {
    // Simple hash combining signal characteristics
    return (frequency / 1000) ^ ((uint32_t)modulation << 16) ^ ((uint32_t)duration << 8);
}

// ============================================================================
// Replay Attack Detection
// ============================================================================

bool subghz_decoder_check_replay_attack(SubGhzScanner* scanner, uint32_t hash) {
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

// ============================================================================
// Jamming Detection
// ============================================================================

void subghz_decoder_check_jamming(SubGhzScanner* scanner, int8_t rssi) {
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
// Signal Type Classification
// ============================================================================

static SubGhzSignalType classify_signal_type(SubGhzProtocolId protocol_id) {
    switch (protocol_id) {
    case SubGhzProtoKeeloq:
    case SubGhzProtoPrinceton:
    case SubGhzProtoNiceFlo:
    case SubGhzProtoCame:
    case SubGhzProtoHormann:
    case SubGhzProtoLinear:
    case SubGhzProtoChamberlain:
        return SubGhzSignalRemote;

    case SubGhzProtoTPMS:
    case SubGhzProtoOregon:
    case SubGhzProtoAcurite:
    case SubGhzProtoLaCrosse:
        return SubGhzSignalSensor;

    default:
        return SubGhzSignalUnknown;
    }
}

// ============================================================================
// Receiver Callback - called when protocol is decoded
// ============================================================================

void subghz_decoder_receiver_callback(
    SubGhzReceiver* receiver,
    SubGhzProtocolDecoderBase* decoder_base,
    void* context) {

    UNUSED(receiver);
    SubGhzScanner* scanner = context;
    if (!scanner || !scanner->running) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Get protocol info
    FuriString* protocol_data = furi_string_alloc();
    subghz_protocol_decoder_base_get_string(decoder_base, protocol_data);

    // Get protocol name from decoder base
    const char* name = decoder_base->protocol->name;

    // Get signal characteristics
    int8_t rssi = subghz_scanner_get_rssi(scanner);

    // Create detection
    SubGhzProtocolId protocol_id = subghz_decoder_identify_protocol(name);
    FlockSubGhzDetection detection = {
        .frequency = scanner->current_frequency,
        .rssi = rssi,
        .modulation = ModulationUnknown,
        .duration_ms = 0,
        .bandwidth = 0,
        .protocol_id = protocol_id,
    };

    if (name) {
        strncpy(detection.protocol_name, name, sizeof(detection.protocol_name) - 1);
    }

    // Check for replay attack
    uint32_t hash = subghz_decoder_compute_signal_hash(
        scanner->current_frequency, detection.modulation, 0);
    SubGhzSignalType signal_type = SubGhzSignalUnknown;

    if (scanner->config.detect_replays && subghz_decoder_check_replay_attack(scanner, hash)) {
        signal_type = SubGhzSignalReplay;
        strncpy(detection.protocol_name, "REPLAY", sizeof(detection.protocol_name) - 1);
        FURI_LOG_W(TAG, "Replay attack detected!");
    } else {
        // Categorize signal type
        signal_type = classify_signal_type(protocol_id);
    }

    scanner->detection_count++;

    // Mark decode as complete - we successfully decoded a signal
    // This allows frequency hopping to proceed
    subghz_decoder_mark_complete(scanner);

    // Copy callback info before releasing mutex to avoid deadlock
    // (user callback might call back into scanner API)
    SubGhzScanCallback callback = scanner->config.callback;
    void* callback_context = scanner->config.callback_context;

    furi_string_free(protocol_data);

    // Release mutex BEFORE invoking callback to prevent deadlock
    furi_mutex_release(scanner->mutex);

    // Now invoke callback outside of mutex protection
    if (callback) {
        callback(&detection, signal_type, callback_context);
    }

    FURI_LOG_I(TAG, "Detection #%lu: %s @ %lu Hz (RSSI: %d, preset: active)",
        scanner->detection_count, detection.protocol_name, detection.frequency, detection.rssi);
}

// ============================================================================
// Decode State Management
// ============================================================================

bool subghz_decoder_is_active(SubGhzScanner* scanner) {
    if (!scanner) return false;

    uint32_t now = furi_get_tick();

    // Hard timeout: force clear after 3 seconds regardless of pulse activity
    // This prevents getting stuck on noisy frequencies like 433.92 MHz
    if (scanner->decode_in_progress) {
        uint32_t decode_duration = now - scanner->decode_start_time;
        if (decode_duration > 3000) {
            FURI_LOG_W(TAG, "Decode timeout after %lu ms - forcing clear", decode_duration);
            scanner->decode_in_progress = false;
            scanner->decode_start_time = 0;
            scanner->last_pulse_time = 0;
            return false;
        }
    }

    // Check if we've received pulses recently (within cooldown period)
    // Only apply if decode was in progress (prevents noise from starting decode)
    if (scanner->decode_in_progress && scanner->last_pulse_time > 0) {
        uint32_t time_since_pulse = now - scanner->last_pulse_time;
        if (time_since_pulse < SUBGHZ_DECODE_COOLDOWN_MS) {
            return true;
        }
        // No recent pulses - decode likely complete
        scanner->decode_in_progress = false;
        scanner->decode_start_time = 0;
        return false;
    }

    return scanner->decode_in_progress;
}

void subghz_decoder_mark_complete(SubGhzScanner* scanner) {
    if (!scanner) return;

    scanner->decode_in_progress = false;
    scanner->decode_start_time = 0;
    // Keep last_pulse_time for cooldown period
    FURI_LOG_D(TAG, "Decode marked complete");
}

// ============================================================================
// Capture Callback - receives raw pulse data from radio
// ============================================================================

// Static counter to rate-limit pulse logging
static uint32_t pulse_log_count = 0;

void subghz_decoder_capture_callback(bool level, uint32_t duration, void* context) {
    SubGhzScanner* scanner = context;

    // Debug: Log first few calls to verify callback is working
    static uint32_t debug_call_count = 0;
    debug_call_count++;
    if (debug_call_count <= 5 || debug_call_count % 10000 == 0) {
        FURI_LOG_I(TAG, "Capture callback #%lu: scanner=%p running=%d receiver=%p dur=%lu",
            debug_call_count, (void*)scanner,
            scanner ? scanner->running : -1,
            scanner ? (void*)scanner->receiver : NULL,
            duration);
    }

    if (!scanner || !scanner->running || !scanner->receiver) return;

    uint32_t now = furi_get_tick();

    // Log every 1000th pulse to show that we ARE receiving something
    pulse_log_count++;
    if (pulse_log_count % 1000 == 1) {
        FURI_LOG_I(TAG, "Pulse #%lu: %lu us @ %lu Hz",
            pulse_log_count, duration, scanner->current_frequency);
    }

    // Track pulse activity - but don't set decode_in_progress here
    // (RF noise was causing permanent "decode in progress" blocking frequency hops)
    if (duration > 100 && duration < 10000) {
        scanner->last_pulse_time = now;
    }

    // Feed raw pulse data to the receiver for decoding
    subghz_receiver_decode(scanner->receiver, level, duration);
}

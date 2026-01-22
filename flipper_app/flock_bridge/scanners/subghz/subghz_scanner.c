/**
 * Sub-GHz Scanner - Core Lifecycle
 * Handles: allocation, start/stop, frequency control, hardware device control.
 * Protocol decoding and signal analysis are in subghz_decoder.c
 */

#include "subghz_internal.h"

// ============================================================================
// Worker Thread
// ============================================================================

static int32_t subghz_scanner_worker(void* context) {
    SubGhzScanner* scanner = context;

    FURI_LOG_I(TAG, "Sub-GHz worker started at %lu Hz", scanner->current_frequency);

    while (!scanner->should_stop) {
        // Check RSSI for jamming detection
        int8_t rssi = subghz_scanner_get_rssi(scanner);
        subghz_decoder_check_jamming(scanner, rssi);

        // The receiver handles detection via callback
        // We just need to keep the thread alive and check for stop

        furi_delay_ms(50);
    }

    FURI_LOG_I(TAG, "Sub-GHz worker stopped");
    return 0;
}

// ============================================================================
// Public API - Protocol Name (wrapper for decoder function)
// ============================================================================

const char* subghz_scanner_get_protocol_name(SubGhzProtocolId proto_id) {
    return subghz_decoder_get_protocol_name(proto_id);
}

// ============================================================================
// Public API - Allocation
// ============================================================================

// ============================================================================
// Preset Helper Functions
// ============================================================================

FuriHalSubGhzPreset subghz_get_furi_preset(SubGhzPresetType preset) {
    switch (preset) {
    case SubGhzPresetOok650:
        return FuriHalSubGhzPresetOok650Async;
    case SubGhzPresetOok270:
        return FuriHalSubGhzPresetOok270Async;
    case SubGhzPreset2FSKDev238:
        return FuriHalSubGhzPreset2FSKDev238Async;
    case SubGhzPreset2FSKDev476:
        return FuriHalSubGhzPreset2FSKDev476Async;
    default:
        return FuriHalSubGhzPresetOok650Async;
    }
}

static const char* subghz_preset_name(SubGhzPresetType preset) {
    switch (preset) {
    case SubGhzPresetOok650:
        return "OOK 650kHz";
    case SubGhzPresetOok270:
        return "OOK 270kHz";
    case SubGhzPreset2FSKDev238:
        return "2-FSK 2.38kHz";
    case SubGhzPreset2FSKDev476:
        return "2-FSK 4.76kHz";
    default:
        return "Unknown";
    }
}

// ============================================================================
// Public API - Allocation
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

    // Default preset settings
    scanner->current_preset = SubGhzPresetOok650;
    scanner->multi_preset_mode = true;  // Enable multi-preset by default

    // Initialize status flags
    scanner->protocol_registry_loaded = false;
    scanner->settings_loaded = false;

    // Get internal CC1101 device
    subghz_devices_init();
    scanner->device = subghz_devices_get_by_name(SUBGHZ_DEVICE_CC1101_INT_NAME);
    if (!scanner->device) {
        FURI_LOG_E(TAG, "CRITICAL: Failed to get CC1101 device - hardware not available");
        furi_mutex_free(scanner->mutex);
        free(scanner);
        return NULL;
    }
    FURI_LOG_I(TAG, "CC1101 device handle acquired: %p", (void*)scanner->device);
    scanner->device_begun = false;  // Will be set true when begin() succeeds in start()

    // Defer heavy allocations (environment, receiver) to start() to reduce peak memory
    scanner->environment = NULL;
    scanner->receiver = NULL;
    scanner->setting = NULL;
    scanner->settings_loaded = false;
    scanner->protocol_registry_loaded = false;

    FURI_LOG_I(TAG, "Sub-GHz scanner allocated (deferred init for memory savings)");

    return scanner;
}

// ============================================================================
// Public API - Deallocation
// ============================================================================

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

    // Deinitialize devices
    subghz_devices_deinit();

    free(scanner);
    FURI_LOG_I(TAG, "Sub-GHz scanner freed");
}

// ============================================================================
// Public API - Configuration
// ============================================================================

void subghz_scanner_configure(SubGhzScanner* scanner, const SubGhzScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(SubGhzScannerConfig));
    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// Public API - Start
// ============================================================================

bool subghz_scanner_start(SubGhzScanner* scanner, uint32_t frequency) {
    if (!scanner || scanner->running || !scanner->device) return false;

    FURI_LOG_I(TAG, "Starting Sub-GHz scanner at %lu Hz with preset %s",
        frequency, subghz_preset_name(scanner->current_preset));

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->current_frequency = frequency;

    // Reset decode protection state
    scanner->last_pulse_time = 0;
    scanner->decode_in_progress = false;
    scanner->decode_start_time = 0;

    // Begin device access (if not already begun at alloc time)
    // NOTE: On mntm firmware, begin() consistently fails.
    // This appears to be a firmware issue where the CC1101 is always marked as "in use".
    if (!scanner->device_begun) {
        if (subghz_devices_begin(scanner->device)) {
            scanner->device_begun = true;
            FURI_LOG_I(TAG, "CC1101 device begun successfully");
        } else {
            FURI_LOG_E(TAG, "Failed to begin CC1101 - device locked (mntm firmware issue?)");
            furi_mutex_release(scanner->mutex);
            return false;
        }
    }

    // Check frequency validity
    if (!subghz_devices_is_frequency_valid(scanner->device, frequency)) {
        FURI_LOG_E(TAG, "Invalid frequency: %lu Hz - check regional settings", frequency);
        subghz_devices_end(scanner->device);
        scanner->device_begun = false;
        furi_mutex_release(scanner->mutex);
        return false;
    }

    // Deferred allocation: allocate environment and receiver now that we're committed
    // This reduces peak memory usage by not allocating until actually needed
    if (!scanner->environment) {
        FURI_LOG_I(TAG, "Allocating SubGHz environment (deferred)");
        scanner->environment = subghz_environment_alloc();
        if (!scanner->environment) {
            FURI_LOG_E(TAG, "Failed to allocate SubGHz environment - OOM");
            subghz_devices_end(scanner->device);
            scanner->device_begun = false;
            furi_mutex_release(scanner->mutex);
            return false;
        }

        // Set protocol registry using built-in protocols
        subghz_environment_set_protocol_registry(
            scanner->environment, (void*)&subghz_protocol_registry);
        scanner->protocol_registry_loaded = true;
        FURI_LOG_I(TAG, "Protocol registry set (built-in)");
    }

    if (!scanner->receiver) {
        FURI_LOG_I(TAG, "Allocating SubGHz receiver (deferred)");
        scanner->receiver = subghz_receiver_alloc_init(scanner->environment);
        if (!scanner->receiver) {
            FURI_LOG_E(TAG, "Failed to allocate SubGHz receiver - OOM");
            subghz_environment_free(scanner->environment);
            scanner->environment = NULL;
            scanner->protocol_registry_loaded = false;
            subghz_devices_end(scanner->device);
            scanner->device_begun = false;
            furi_mutex_release(scanner->mutex);
            return false;
        }

        subghz_receiver_set_filter(scanner->receiver, SubGhzProtocolFlag_Decodable);
        subghz_receiver_set_rx_callback(
            scanner->receiver, subghz_decoder_receiver_callback, scanner);
        FURI_LOG_I(TAG, "SubGHz receiver initialized with callback");
    }

    // Reset and configure device
    subghz_devices_reset(scanner->device);
    subghz_devices_idle(scanner->device);

    // Load preset - use current preset type for modulation diversity
    FuriHalSubGhzPreset furi_preset = subghz_get_furi_preset(scanner->current_preset);
    subghz_devices_load_preset(scanner->device, furi_preset, NULL);
    FURI_LOG_D(TAG, "Loaded preset: %s", subghz_preset_name(scanner->current_preset));

    // Set frequency
    subghz_devices_set_frequency(scanner->device, frequency);

    // Reset receiver
    subghz_receiver_reset(scanner->receiver);

    // Start async RX with capture callback
    FURI_LOG_I(TAG, "Starting async RX at %lu Hz with callback %p", frequency, subghz_decoder_capture_callback);
    subghz_devices_start_async_rx(scanner->device, subghz_decoder_capture_callback, scanner);
    FURI_LOG_I(TAG, "Async RX started - radio should now be receiving");

    scanner->running = true;
    scanner->should_stop = false;

    // Start worker thread
    scanner->worker_thread = furi_thread_alloc_ex(
        "SubGhzScanWorker", 1024, subghz_scanner_worker, scanner);  // Reduced from 2048
    furi_thread_start(scanner->worker_thread);

    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "Sub-GHz scanner started successfully");
    return true;
}

// ============================================================================
// Public API - Stop
// ============================================================================

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
    if (scanner->device) {
        subghz_devices_stop_async_rx(scanner->device);
        subghz_devices_idle(scanner->device);
        subghz_devices_sleep(scanner->device);
        if (scanner->device_begun) {
            subghz_devices_end(scanner->device);
            scanner->device_begun = false;
            FURI_LOG_I(TAG, "CC1101 device released");
        }
    }

    scanner->running = false;

    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "Sub-GHz scanner stopped");
}

// ============================================================================
// Public API - Frequency Control
// ============================================================================

bool subghz_scanner_set_frequency(SubGhzScanner* scanner, uint32_t frequency) {
    if (!scanner || !scanner->device) return false;

    // Log if scanner is not running - indicates a problem
    if (!scanner->running) {
        // Don't try to auto-start - the scheduler should do that
        FURI_LOG_W(TAG, "set_frequency called but scanner not running! (freq=%lu)", frequency);
        return false;
    }

    if (!subghz_devices_is_frequency_valid(scanner->device, frequency)) {
        FURI_LOG_E(TAG, "Invalid frequency: %lu Hz", frequency);
        return false;
    }

    // Check if we should delay the frequency change to protect active decoding
    if (subghz_decoder_is_active(scanner)) {
        FURI_LOG_D(TAG, "Decode in progress - deferring frequency change from %lu to %lu Hz",
            scanner->current_frequency, frequency);
        // Return true but don't actually change - caller should retry
        // This prevents breaking mid-decode signals
        return true;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    if (scanner->running) {
        // Stop RX, switch frequency, restart
        subghz_devices_stop_async_rx(scanner->device);
        subghz_devices_idle(scanner->device);

        scanner->current_frequency = frequency;
        subghz_devices_set_frequency(scanner->device, frequency);

        // Reset decode state for new frequency
        scanner->last_pulse_time = 0;
        scanner->decode_in_progress = false;
        scanner->decode_start_time = 0;

        subghz_receiver_reset(scanner->receiver);
        subghz_devices_start_async_rx(scanner->device, subghz_decoder_capture_callback, scanner);

        FURI_LOG_I(TAG, "Async RX restarted at %lu Hz (callback: %p)", frequency, (void*)subghz_decoder_capture_callback);
    } else {
        scanner->current_frequency = frequency;
    }

    furi_mutex_release(scanner->mutex);

    return true;
}

uint32_t subghz_scanner_get_frequency(SubGhzScanner* scanner) {
    if (!scanner) return 0;
    return scanner->current_frequency;
}

// ============================================================================
// Public API - State Queries
// ============================================================================

bool subghz_scanner_is_running(SubGhzScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

int8_t subghz_scanner_get_rssi(SubGhzScanner* scanner) {
    if (!scanner || !scanner->running || !scanner->device) return -128;
    return (int8_t)subghz_devices_get_rssi(scanner->device);
}

uint32_t subghz_scanner_get_detection_count(SubGhzScanner* scanner) {
    if (!scanner) return 0;
    return scanner->detection_count;
}

// ============================================================================
// Public API - Memory Management
// ============================================================================

void subghz_scanner_reset_decoder(SubGhzScanner* scanner) {
    if (!scanner || !scanner->running || !scanner->receiver) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Reset the receiver to clear all accumulated decoder state
    // This is critical for preventing memory growth from RF noise
    subghz_receiver_reset(scanner->receiver);

    // Clear signal history to free any accumulated entries
    memset(scanner->signal_history, 0, sizeof(scanner->signal_history));
    scanner->history_head = 0;

    // Reset jamming detection state
    scanner->high_rssi_start = 0;
    scanner->jamming_detected = false;

    // Reset decode protection state
    scanner->last_pulse_time = 0;
    scanner->decode_in_progress = false;
    scanner->decode_start_time = 0;

    FURI_LOG_D(TAG, "Decoder state reset (memory cleanup)");

    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// Public API - Preset Management
// ============================================================================

bool subghz_scanner_set_preset(SubGhzScanner* scanner, SubGhzPresetType preset) {
    if (!scanner || preset >= SubGhzPresetTypeCount) return false;

    // Don't switch if decode is in progress
    if (scanner->running && subghz_decoder_is_active(scanner)) {
        FURI_LOG_D(TAG, "Decode in progress - deferring preset change");
        return false;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->current_preset = preset;

    if (scanner->running && scanner->device) {
        // Hot-switch the preset while running
        subghz_devices_stop_async_rx(scanner->device);
        subghz_devices_idle(scanner->device);

        FuriHalSubGhzPreset furi_preset = subghz_get_furi_preset(preset);
        subghz_devices_load_preset(scanner->device, furi_preset, NULL);

        subghz_receiver_reset(scanner->receiver);
        subghz_devices_start_async_rx(scanner->device, subghz_decoder_capture_callback, scanner);

        FURI_LOG_I(TAG, "Preset switched to %s", subghz_preset_name(preset));
    }

    furi_mutex_release(scanner->mutex);
    return true;
}

SubGhzPresetType subghz_scanner_get_preset(SubGhzScanner* scanner) {
    if (!scanner) return SubGhzPresetOok650;
    return scanner->current_preset;
}

void subghz_scanner_cycle_preset(SubGhzScanner* scanner) {
    if (!scanner) return;

    // Cycle to next preset
    SubGhzPresetType next = (scanner->current_preset + 1) % SubGhzPresetTypeCount;
    subghz_scanner_set_preset(scanner, next);
}

void subghz_scanner_recreate_receiver(SubGhzScanner* scanner) {
    if (!scanner) return;

    bool was_running = scanner->running;
    uint32_t freq = scanner->current_frequency;

    // Stop if running
    if (was_running) {
        subghz_scanner_stop(scanner);
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Free old receiver
    if (scanner->receiver) {
        subghz_receiver_free(scanner->receiver);
        scanner->receiver = NULL;
    }

    // Create new receiver - this frees all internal decoder allocations
    scanner->receiver = subghz_receiver_alloc_init(scanner->environment);
    if (scanner->receiver) {
        subghz_receiver_set_filter(scanner->receiver, SubGhzProtocolFlag_Decodable);
        subghz_receiver_set_rx_callback(scanner->receiver, subghz_decoder_receiver_callback, scanner);
        FURI_LOG_I(TAG, "SubGHz receiver recreated (memory freed)");
    } else {
        FURI_LOG_E(TAG, "Failed to recreate SubGHz receiver!");
    }

    // Clear history
    memset(scanner->signal_history, 0, sizeof(scanner->signal_history));
    scanner->history_head = 0;
    scanner->high_rssi_start = 0;
    scanner->jamming_detected = false;

    furi_mutex_release(scanner->mutex);

    // Restart if was running
    if (was_running && scanner->receiver) {
        subghz_scanner_start(scanner, freq);
    }
}

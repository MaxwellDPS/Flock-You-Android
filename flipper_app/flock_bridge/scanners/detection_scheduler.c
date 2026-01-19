#include "detection_scheduler.h"
#include "subghz_scanner.h"
#include "ble_scanner.h"
#include "ir_scanner.h"
#include "nfc_scanner.h"
#include <string.h>
#include <stdlib.h>

#define TAG "DetectionScheduler"

// ============================================================================
// Scheduler Structure
// ============================================================================

struct DetectionScheduler {
    SchedulerConfig config;
    SchedulerStats stats;

    // Scanners
    SubGhzScanner* subghz;
    BleScanner* ble;
    IrScanner* ir;
    NfcScanner* nfc;

    // State
    bool running;
    ScanSlotType current_slot;
    uint8_t subghz_frequency_index;
    uint32_t last_ble_scan;
    uint32_t start_time;

    // Thread
    FuriThread* scheduler_thread;
    FuriMutex* mutex;
    volatile bool should_stop;

    // Pause flags
    bool subghz_paused;
    bool ble_paused;
};

// ============================================================================
// Internal Callbacks - Forward to user callbacks
// ============================================================================

static void scheduler_subghz_callback(
    const FlockSubGhzDetection* detection,
    SubGhzSignalType signal_type,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.subghz_detections++;
    furi_mutex_release(scheduler->mutex);

    if (scheduler->config.subghz_callback) {
        scheduler->config.subghz_callback(detection, scheduler->config.callback_context);
    }

    FURI_LOG_I(TAG, "Sub-GHz detection: %s @ %lu Hz (type: %d)",
        detection->protocol_name, detection->frequency, signal_type);
}

static void scheduler_ble_callback(
    const BleDeviceExtended* device,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.ble_devices_found++;
    furi_mutex_release(scheduler->mutex);

    if (scheduler->config.ble_callback) {
        scheduler->config.ble_callback(&device->base, scheduler->config.callback_context);
    }

    if (device->tracker_type != BleTrackerNone) {
        FURI_LOG_I(TAG, "BLE tracker: %s (RSSI: %d)",
            ble_scanner_get_tracker_name(device->tracker_type), device->base.rssi);
    }
}

static void scheduler_ir_callback(
    const FlockIrDetection* detection,
    IrSignalType signal_type,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.ir_signals_captured++;
    furi_mutex_release(scheduler->mutex);

    if (scheduler->config.ir_callback) {
        scheduler->config.ir_callback(detection, scheduler->config.callback_context);
    }

    FURI_LOG_D(TAG, "IR: %s (type: %d)", detection->protocol_name, signal_type);
}

static void scheduler_nfc_callback(
    const NfcDetectionExtended* detection,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.nfc_tags_detected++;
    furi_mutex_release(scheduler->mutex);

    if (scheduler->config.nfc_callback) {
        scheduler->config.nfc_callback(&detection->base, scheduler->config.callback_context);
    }

    FURI_LOG_I(TAG, "NFC: %s (UID len: %d)",
        detection->base.type_name, detection->base.uid_len);
}

// ============================================================================
// Scheduler Thread - Main Loop
// ============================================================================

static int32_t scheduler_thread_func(void* context) {
    DetectionScheduler* scheduler = context;

    FURI_LOG_I(TAG, "Detection scheduler started");

    uint32_t last_frequency_hop = 0;
    uint32_t last_ble_scan = 0;

    // Start passive scanners (IR and NFC can run alongside Sub-GHz)
    if (scheduler->config.enable_ir && scheduler->ir) {
        ir_scanner_start(scheduler->ir);
        FURI_LOG_I(TAG, "IR scanner started (passive)");
    }

    if (scheduler->config.enable_nfc && scheduler->nfc) {
        nfc_scanner_start(scheduler->nfc);
        FURI_LOG_I(TAG, "NFC scanner started (passive)");
    }

    // Start Sub-GHz at first frequency
    if (scheduler->config.enable_subghz && scheduler->subghz) {
        uint32_t freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
        subghz_scanner_start(scheduler->subghz, freq);
        FURI_LOG_I(TAG, "Sub-GHz scanner started at %lu Hz", freq);
    }

    while (!scheduler->should_stop) {
        uint32_t now = furi_get_tick();

        // ====================================================================
        // Sub-GHz Frequency Hopping (continuous background)
        // ====================================================================
        if (scheduler->config.enable_subghz &&
            scheduler->subghz &&
            !scheduler->subghz_paused &&
            scheduler->config.subghz_continuous) {

            if ((now - last_frequency_hop) >= scheduler->config.subghz_hop_interval_ms) {
                // Hop to next frequency
                scheduler->subghz_frequency_index =
                    (scheduler->subghz_frequency_index + 1) % SUBGHZ_FREQUENCY_COUNT;

                uint32_t new_freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
                subghz_scanner_set_frequency(scheduler->subghz, new_freq);

                furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                scheduler->stats.subghz_frequencies_scanned++;
                furi_mutex_release(scheduler->mutex);

                last_frequency_hop = now;

                FURI_LOG_D(TAG, "Sub-GHz hop to %lu Hz", new_freq);
            }
        }

        // ====================================================================
        // BLE Burst Scanning (periodic)
        // ====================================================================
        if (scheduler->config.enable_ble &&
            scheduler->ble &&
            !scheduler->ble_paused) {

            // Check if it's time for a BLE scan
            if ((now - last_ble_scan) >= scheduler->config.ble_scan_interval_ms) {
                // BLE and BT Serial share the radio
                // Need to pause BT Serial during BLE scan if using it

                // For USB connection mode, we can scan freely
                // For BT connection mode, we need to be careful

                if (!ble_scanner_is_running(scheduler->ble)) {
                    FURI_LOG_I(TAG, "Starting BLE burst scan");

                    // Note: In practice, may need to temporarily stop Sub-GHz
                    // during BLE scan if there's interference
                    // For now, let them run concurrently

                    ble_scanner_start(scheduler->ble);
                    last_ble_scan = now;

                    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                    scheduler->stats.ble_scans_completed++;
                    furi_mutex_release(scheduler->mutex);
                }
            }
        }

        // ====================================================================
        // Update Stats
        // ====================================================================
        furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
        scheduler->stats.uptime_seconds = (now - scheduler->start_time) / 1000;
        furi_mutex_release(scheduler->mutex);

        // Sleep for tick interval
        furi_delay_ms(SCHEDULER_TICK_MS);
    }

    // ========================================================================
    // Cleanup - Stop all scanners
    // ========================================================================
    FURI_LOG_I(TAG, "Stopping all scanners");

    if (scheduler->subghz && subghz_scanner_is_running(scheduler->subghz)) {
        subghz_scanner_stop(scheduler->subghz);
    }
    if (scheduler->ble && ble_scanner_is_running(scheduler->ble)) {
        ble_scanner_stop(scheduler->ble);
    }
    if (scheduler->ir && ir_scanner_is_running(scheduler->ir)) {
        ir_scanner_stop(scheduler->ir);
    }
    if (scheduler->nfc && nfc_scanner_is_running(scheduler->nfc)) {
        nfc_scanner_stop(scheduler->nfc);
    }

    FURI_LOG_I(TAG, "Detection scheduler stopped");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

DetectionScheduler* detection_scheduler_alloc(void) {
    DetectionScheduler* scheduler = malloc(sizeof(DetectionScheduler));
    if (!scheduler) return NULL;

    memset(scheduler, 0, sizeof(DetectionScheduler));

    scheduler->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default configuration
    scheduler->config.enable_subghz = true;
    scheduler->config.enable_ble = true;
    scheduler->config.enable_ir = true;
    scheduler->config.enable_nfc = true;
    scheduler->config.subghz_hop_interval_ms = SUBGHZ_HOP_INTERVAL_MS;
    scheduler->config.subghz_continuous = true;
    scheduler->config.ble_scan_duration_ms = BLE_SCAN_DURATION_MS;
    scheduler->config.ble_scan_interval_ms = BLE_SCAN_INTERVAL_MS;
    scheduler->config.ble_detect_trackers = true;

    // Allocate scanners
    scheduler->subghz = subghz_scanner_alloc();
    scheduler->ble = ble_scanner_alloc();
    scheduler->ir = ir_scanner_alloc();
    scheduler->nfc = nfc_scanner_alloc();

    // Configure scanner callbacks
    if (scheduler->subghz) {
        SubGhzScannerConfig subghz_config = {
            .detect_replays = true,
            .detect_jamming = true,
            .rssi_threshold = -90,
            .callback = scheduler_subghz_callback,
            .callback_context = scheduler,
        };
        subghz_scanner_configure(scheduler->subghz, &subghz_config);
    }

    if (scheduler->ble) {
        BleScannerConfig ble_config = {
            .detect_trackers = true,
            .detect_spam = true,
            .detect_following = true,
            .rssi_threshold = -85,
            .scan_duration_ms = BLE_SCAN_DURATION_MS,
            .callback = scheduler_ble_callback,
            .callback_context = scheduler,
        };
        ble_scanner_configure(scheduler->ble, &ble_config);
    }

    if (scheduler->ir) {
        IrScannerConfig ir_config = {
            .detect_brute_force = true,
            .detect_replay = true,
            .brute_force_threshold = 20,
            .replay_window_ms = 5000,
            .callback = scheduler_ir_callback,
            .callback_context = scheduler,
        };
        ir_scanner_configure(scheduler->ir, &ir_config);
    }

    if (scheduler->nfc) {
        NfcScannerConfig nfc_config = {
            .detect_cards = true,
            .detect_tags = true,
            .detect_phones = true,
            .continuous_poll = true,
            .callback = scheduler_nfc_callback,
            .callback_context = scheduler,
        };
        nfc_scanner_configure(scheduler->nfc, &nfc_config);
    }

    FURI_LOG_I(TAG, "Detection scheduler allocated");
    return scheduler;
}

void detection_scheduler_free(DetectionScheduler* scheduler) {
    if (!scheduler) return;

    detection_scheduler_stop(scheduler);

    // Free scanners
    if (scheduler->subghz) subghz_scanner_free(scheduler->subghz);
    if (scheduler->ble) ble_scanner_free(scheduler->ble);
    if (scheduler->ir) ir_scanner_free(scheduler->ir);
    if (scheduler->nfc) nfc_scanner_free(scheduler->nfc);

    if (scheduler->mutex) furi_mutex_free(scheduler->mutex);

    free(scheduler);
    FURI_LOG_I(TAG, "Detection scheduler freed");
}

void detection_scheduler_configure(
    DetectionScheduler* scheduler,
    const SchedulerConfig* config) {

    if (!scheduler || !config) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    memcpy(&scheduler->config, config, sizeof(SchedulerConfig));
    furi_mutex_release(scheduler->mutex);
}

bool detection_scheduler_start(DetectionScheduler* scheduler) {
    if (!scheduler || scheduler->running) return false;

    FURI_LOG_I(TAG, "Starting detection scheduler");

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);

    scheduler->running = true;
    scheduler->should_stop = false;
    scheduler->start_time = furi_get_tick();
    scheduler->subghz_frequency_index = 0;

    // Start scheduler thread
    scheduler->scheduler_thread = furi_thread_alloc_ex(
        "DetectionScheduler", 4096, scheduler_thread_func, scheduler);
    furi_thread_start(scheduler->scheduler_thread);

    furi_mutex_release(scheduler->mutex);

    FURI_LOG_I(TAG, "Detection scheduler started");
    return true;
}

void detection_scheduler_stop(DetectionScheduler* scheduler) {
    if (!scheduler || !scheduler->running) return;

    FURI_LOG_I(TAG, "Stopping detection scheduler");

    scheduler->should_stop = true;

    if (scheduler->scheduler_thread) {
        furi_thread_join(scheduler->scheduler_thread);
        furi_thread_free(scheduler->scheduler_thread);
        scheduler->scheduler_thread = NULL;
    }

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->running = false;
    furi_mutex_release(scheduler->mutex);

    FURI_LOG_I(TAG, "Detection scheduler stopped");
}

bool detection_scheduler_is_running(DetectionScheduler* scheduler) {
    if (!scheduler) return false;
    return scheduler->running;
}

void detection_scheduler_get_stats(
    DetectionScheduler* scheduler,
    SchedulerStats* stats) {

    if (!scheduler || !stats) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    memcpy(stats, &scheduler->stats, sizeof(SchedulerStats));
    furi_mutex_release(scheduler->mutex);
}

ScanSlotType detection_scheduler_get_current_slot(DetectionScheduler* scheduler) {
    if (!scheduler) return ScanSlotSubGhz;
    return scheduler->current_slot;
}

uint32_t detection_scheduler_get_current_frequency(DetectionScheduler* scheduler) {
    if (!scheduler || !scheduler->subghz) return 0;
    return subghz_scanner_get_frequency(scheduler->subghz);
}

void detection_scheduler_set_frequency(
    DetectionScheduler* scheduler,
    uint32_t frequency) {

    if (!scheduler || !scheduler->subghz) return;

    subghz_scanner_set_frequency(scheduler->subghz, frequency);

    // Find index in frequency table
    for (size_t i = 0; i < SUBGHZ_FREQUENCY_COUNT; i++) {
        if (SUBGHZ_FREQUENCIES[i] == frequency) {
            scheduler->subghz_frequency_index = i;
            break;
        }
    }
}

void detection_scheduler_pause_subghz(DetectionScheduler* scheduler, bool pause) {
    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->subghz_paused = pause;

    if (pause && scheduler->subghz && subghz_scanner_is_running(scheduler->subghz)) {
        subghz_scanner_stop(scheduler->subghz);
    } else if (!pause && scheduler->subghz && !subghz_scanner_is_running(scheduler->subghz)) {
        uint32_t freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
        subghz_scanner_start(scheduler->subghz, freq);
    }

    furi_mutex_release(scheduler->mutex);
}

void detection_scheduler_pause_ble(DetectionScheduler* scheduler, bool pause) {
    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->ble_paused = pause;

    if (pause && scheduler->ble && ble_scanner_is_running(scheduler->ble)) {
        ble_scanner_stop(scheduler->ble);
    }

    furi_mutex_release(scheduler->mutex);
}

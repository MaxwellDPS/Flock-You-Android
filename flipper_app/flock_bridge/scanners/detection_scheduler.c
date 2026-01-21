/**
 * Detection Scheduler
 *
 * Public API for the multi-scanner detection scheduler.
 * Coordinates Sub-GHz, BLE, WiFi, IR, and NFC scanning.
 */

#include "sched_internal.h"
#include "../helpers/external_radio.h"
#include <string.h>
#include <stdlib.h>

#define TAG "DetectionScheduler"

// ============================================================================
// Allocation and Configuration
// ============================================================================

DetectionScheduler* detection_scheduler_alloc(void) {
    DetectionScheduler* scheduler = malloc(sizeof(DetectionScheduler));
    if (!scheduler) return NULL;

    memset(scheduler, 0, sizeof(DetectionScheduler));

    scheduler->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default configuration
    scheduler->config.enable_subghz = true;
    scheduler->config.enable_ble = true;
    scheduler->config.enable_wifi = true;
    scheduler->config.enable_ir = true;
    scheduler->config.enable_nfc = true;
    scheduler->config.subghz_hop_interval_ms = SUBGHZ_HOP_INTERVAL_MS;
    scheduler->config.subghz_continuous = true;
    scheduler->config.ble_scan_duration_ms = BLE_SCAN_DURATION_MS;
    scheduler->config.ble_scan_interval_ms = BLE_SCAN_INTERVAL_MS;
    scheduler->config.ble_detect_trackers = true;
    scheduler->config.wifi_scan_interval_ms = 10000;
    scheduler->config.wifi_channel = 0;
    scheduler->config.wifi_monitor_probes = true;
    scheduler->config.wifi_detect_deauths = true;

    // Default radio sources: Auto (prefer external if available)
    scheduler->config.radio_sources.subghz_source = RadioSourceAuto;
    scheduler->config.radio_sources.ble_source = RadioSourceAuto;
    scheduler->config.radio_sources.wifi_source = RadioSourceExternal;

    FURI_LOG_I(TAG, "Detection scheduler allocated (scanners deferred)");
    return scheduler;
}

void detection_scheduler_free(DetectionScheduler* scheduler) {
    if (!scheduler) return;

    detection_scheduler_stop(scheduler);

    if (scheduler->subghz_internal) subghz_scanner_free(scheduler->subghz_internal);
    if (scheduler->ble_internal) ble_scanner_free(scheduler->ble_internal);
    if (scheduler->ir) ir_scanner_free(scheduler->ir);
    if (scheduler->nfc) flock_nfc_scanner_free(scheduler->nfc);
    if (scheduler->wifi) wifi_scanner_free(scheduler->wifi);
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

// ============================================================================
// External Radio Management
// ============================================================================

void detection_scheduler_set_external_radio(
    DetectionScheduler* scheduler,
    ExternalRadioManager* radio_manager) {

    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);

    scheduler->external_radio = radio_manager;

    if (radio_manager && external_radio_is_connected(radio_manager)) {
        uint32_t caps = external_radio_get_capabilities(radio_manager);
        if ((caps & EXT_RADIO_CAP_WIFI_SCAN) && !scheduler->wifi) {
            scheduler->wifi = wifi_scanner_alloc(radio_manager);
            FURI_LOG_I(TAG, "WiFi scanner created (external ESP32 detected)");
        }
    }

    furi_mutex_release(scheduler->mutex);
}

bool detection_scheduler_has_external_radio(DetectionScheduler* scheduler) {
    if (!scheduler || !scheduler->external_radio) return false;
    return external_radio_is_connected(scheduler->external_radio);
}

uint32_t detection_scheduler_get_external_capabilities(DetectionScheduler* scheduler) {
    if (!scheduler || !scheduler->external_radio) return 0;
    return external_radio_get_capabilities(scheduler->external_radio);
}

// ============================================================================
// Scanner Allocation (on-demand)
// ============================================================================

static void allocate_scanners_on_demand(DetectionScheduler* scheduler) {
    // SubGHz scanner
    if (scheduler->config.enable_subghz && !scheduler->subghz_internal) {
        scheduler->subghz_internal = subghz_scanner_alloc();
        if (scheduler->subghz_internal) {
            SubGhzScannerConfig subghz_config = {
                .detect_replays = true,
                .detect_jamming = true,
                .rssi_threshold = -90,
                .callback = scheduler_subghz_callback_impl,
                .callback_context = scheduler,
            };
            subghz_scanner_configure(scheduler->subghz_internal, &subghz_config);
            FURI_LOG_I(TAG, "SubGHz scanner allocated (on-demand)");
        } else {
            FURI_LOG_E(TAG, "Failed to allocate SubGHz scanner");
        }
    }

    // BLE scanner
    if (scheduler->config.enable_ble && !scheduler->ble_internal) {
        scheduler->ble_internal = ble_scanner_alloc();
        if (scheduler->ble_internal) {
            BleScannerConfig ble_config = {
                .detect_trackers = true,
                .detect_spam = true,
                .detect_following = true,
                .rssi_threshold = -85,
                .scan_duration_ms = BLE_SCAN_DURATION_MS,
                .callback = scheduler_ble_callback_impl,
                .callback_context = scheduler,
            };
            ble_scanner_configure(scheduler->ble_internal, &ble_config);
            FURI_LOG_I(TAG, "BLE scanner allocated (on-demand)");
        } else {
            FURI_LOG_E(TAG, "Failed to allocate BLE scanner");
        }
    }

    // IR scanner
    if (scheduler->config.enable_ir && !scheduler->ir) {
        scheduler->ir = ir_scanner_alloc();
        if (scheduler->ir) {
            IrScannerConfig ir_config = {
                .detect_brute_force = true,
                .detect_replay = true,
                .brute_force_threshold = 20,
                .replay_window_ms = 5000,
                .callback = scheduler_ir_callback_impl,
                .callback_context = scheduler,
            };
            ir_scanner_configure(scheduler->ir, &ir_config);
            FURI_LOG_I(TAG, "IR scanner allocated (on-demand)");
        } else {
            FURI_LOG_E(TAG, "Failed to allocate IR scanner");
        }
    }

    // NFC scanner
    if (scheduler->config.enable_nfc && !scheduler->nfc) {
        scheduler->nfc = flock_nfc_scanner_alloc();
        if (scheduler->nfc) {
            FlockNfcScannerConfig nfc_config = {
                .detect_cards = true,
                .detect_tags = true,
                .detect_phones = true,
                .continuous_poll = true,
                .callback = scheduler_nfc_callback_impl,
                .callback_context = scheduler,
            };
            flock_nfc_scanner_configure(scheduler->nfc, &nfc_config);
            FURI_LOG_I(TAG, "NFC scanner allocated (on-demand)");
        } else {
            FURI_LOG_E(TAG, "Failed to allocate NFC scanner");
        }
    }
}

// ============================================================================
// Start/Stop
// ============================================================================

bool detection_scheduler_start(DetectionScheduler* scheduler) {
    if (!scheduler || scheduler->running) return false;

    FURI_LOG_I(TAG, "Starting detection scheduler");

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);

    allocate_scanners_on_demand(scheduler);

    scheduler->running = true;
    scheduler->should_stop = false;
    scheduler->start_time = furi_get_tick();
    scheduler->subghz_frequency_index = 0;

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

// ============================================================================
// Statistics
// ============================================================================

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

// ============================================================================
// Frequency Control
// ============================================================================

uint32_t detection_scheduler_get_current_frequency(DetectionScheduler* scheduler) {
    if (!scheduler) return 0;

    if (scheduler->subghz_internal) {
        return subghz_scanner_get_frequency(scheduler->subghz_internal);
    }

    return SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
}

void detection_scheduler_set_frequency(
    DetectionScheduler* scheduler,
    uint32_t frequency) {

    if (!scheduler) return;

    if (scheduler->subghz_internal) {
        subghz_scanner_set_frequency(scheduler->subghz_internal, frequency);
    }

    if (scheduler->external_radio && external_radio_is_connected(scheduler->external_radio)) {
        uint8_t freq_cmd[4];
        freq_cmd[0] = (frequency >> 24) & 0xFF;
        freq_cmd[1] = (frequency >> 16) & 0xFF;
        freq_cmd[2] = (frequency >> 8) & 0xFF;
        freq_cmd[3] = frequency & 0xFF;
        external_radio_send_command(
            scheduler->external_radio,
            ExtRadioCmdSubGhzSetFreq,
            freq_cmd, 4);
    }

    for (size_t i = 0; i < SUBGHZ_FREQUENCY_COUNT; i++) {
        if (SUBGHZ_FREQUENCIES[i] == frequency) {
            scheduler->subghz_frequency_index = i;
            break;
        }
    }
}

// ============================================================================
// Pause/Resume
// ============================================================================

void detection_scheduler_pause_subghz(DetectionScheduler* scheduler, bool pause) {
    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->subghz_paused = pause;

    if (pause) {
        if (scheduler->subghz_internal && subghz_scanner_is_running(scheduler->subghz_internal)) {
            subghz_scanner_stop(scheduler->subghz_internal);
        }
        if (scheduler->external_radio && external_radio_is_connected(scheduler->external_radio)) {
            external_radio_send_command(scheduler->external_radio, ExtRadioCmdSubGhzRxStop, NULL, 0);
        }
    } else {
        if (scheduler->subghz_internal && !subghz_scanner_is_running(scheduler->subghz_internal)) {
            uint32_t freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
            subghz_scanner_start(scheduler->subghz_internal, freq);
        }
        if (scheduler->external_radio && external_radio_is_connected(scheduler->external_radio)) {
            external_radio_send_command(scheduler->external_radio, ExtRadioCmdSubGhzRxStart, NULL, 0);
        }
    }

    furi_mutex_release(scheduler->mutex);
}

void detection_scheduler_pause_ble(DetectionScheduler* scheduler, bool pause) {
    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->ble_paused = pause;

    if (pause) {
        if (scheduler->ble_internal && ble_scanner_is_running(scheduler->ble_internal)) {
            ble_scanner_stop(scheduler->ble_internal);
        }
        if (scheduler->external_radio && external_radio_is_connected(scheduler->external_radio)) {
            external_radio_send_command(scheduler->external_radio, ExtRadioCmdBleScanStop, NULL, 0);
        }
    }

    furi_mutex_release(scheduler->mutex);
}

void detection_scheduler_pause_wifi(DetectionScheduler* scheduler, bool pause) {
    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->wifi_paused = pause;

    if (pause) {
        if (scheduler->wifi && wifi_scanner_is_running(scheduler->wifi)) {
            wifi_scanner_stop(scheduler->wifi);
        }
    } else {
        if (scheduler->wifi && !wifi_scanner_is_running(scheduler->wifi)) {
            wifi_scanner_start(scheduler->wifi);
        }
    }

    furi_mutex_release(scheduler->mutex);
}

// ============================================================================
// Radio Source Settings
// ============================================================================

void detection_scheduler_set_radio_sources(
    DetectionScheduler* scheduler,
    const RadioSourceSettings* settings) {

    if (!scheduler || !settings) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    memcpy(&scheduler->config.radio_sources, settings, sizeof(RadioSourceSettings));
    furi_mutex_release(scheduler->mutex);

    FURI_LOG_I(TAG, "Radio sources updated: SubGHz=%d, BLE=%d, WiFi=%d",
        settings->subghz_source, settings->ble_source, settings->wifi_source);
}

void detection_scheduler_get_radio_sources(
    DetectionScheduler* scheduler,
    RadioSourceSettings* settings) {

    if (!scheduler || !settings) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    memcpy(settings, &scheduler->config.radio_sources, sizeof(RadioSourceSettings));
    furi_mutex_release(scheduler->mutex);
}

const char* detection_scheduler_get_source_name(RadioSourceMode mode) {
    switch (mode) {
    case RadioSourceAuto: return "Auto";
    case RadioSourceInternal: return "Internal";
    case RadioSourceExternal: return "External";
    case RadioSourceBoth: return "Both";
    default: return "Unknown";
    }
}

// ============================================================================
// BT Serial Integration
// ============================================================================

void detection_scheduler_set_bt_serial(
    DetectionScheduler* scheduler,
    FlockBtSerial* bt_serial) {

    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->bt_serial = bt_serial;
    furi_mutex_release(scheduler->mutex);

    if (bt_serial) {
        FURI_LOG_I(TAG, "BT serial set - time-multiplexed BLE scanning enabled");
    } else {
        FURI_LOG_I(TAG, "BT serial cleared - time-multiplexed BLE scanning disabled");
    }
}

bool detection_scheduler_can_ble_scan(DetectionScheduler* scheduler) {
    if (!scheduler) return false;

    bool ext_available = scheduler->external_radio &&
                         external_radio_is_connected(scheduler->external_radio);
    uint32_t ext_caps = ext_available ?
        external_radio_get_capabilities(scheduler->external_radio) : 0;
    bool ext_ble_available = (ext_caps & EXT_RADIO_CAP_BLE_SCAN) != 0;

    if (ext_ble_available) return true;
    if (scheduler->ble_internal && scheduler->bt_serial) return true;

    return false;
}

// ============================================================================
// USB CDC Integration (for time-multiplexed IR scanning)
// ============================================================================

void detection_scheduler_set_usb_cdc(
    DetectionScheduler* scheduler,
    FlockUsbCdc* usb_cdc) {

    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->usb_cdc = usb_cdc;
    furi_mutex_release(scheduler->mutex);

    if (usb_cdc) {
        FURI_LOG_I(TAG, "USB CDC set - time-multiplexed IR scanning enabled");
        FURI_LOG_I(TAG, "IR will use burst mode: %lu ms every %lu ms",
            IR_SCAN_DURATION_MS, IR_SCAN_INTERVAL_MS);
    } else {
        FURI_LOG_I(TAG, "USB CDC cleared - IR scanning will run continuously");
    }
}

bool detection_scheduler_can_ir_scan(DetectionScheduler* scheduler) {
    if (!scheduler) return false;

    // IR scanning is always possible:
    // - If USB CDC is not active, IR runs continuously
    // - If USB CDC is active, IR uses time-multiplexed burst mode
    // The only case where it wouldn't work is if the IR scanner itself failed to allocate
    return scheduler->ir != NULL;
}

#include "detection_scheduler.h"
#include "subghz_scanner.h"
#include "ble_scanner.h"
#include "ir_scanner.h"
#include "nfc_scanner.h"
#include "wifi_scanner.h"
#include <string.h>
#include <stdlib.h>

#define TAG "DetectionScheduler"

// ============================================================================
// Scheduler Structure
// ============================================================================

struct DetectionScheduler {
    SchedulerConfig config;
    SchedulerStats stats;

    // Internal scanners (Flipper's built-in radios)
    SubGhzScanner* subghz_internal;
    BleScanner* ble_internal;
    IrScanner* ir;
    NfcScanner* nfc;

    // External radio manager
    ExternalRadioManager* external_radio;

    // External scanners (via ESP32/CC1101/nRF24)
    WifiScanner* wifi;
    // Note: External Sub-GHz and BLE would use same external_radio
    // but with different command sets

    // Active scanner pointers (point to whichever is in use)
    SubGhzScanner* subghz_active;
    BleScanner* ble_active;

    // State
    bool running;
    ScanSlotType current_slot;
    uint8_t subghz_frequency_index;
    uint32_t last_ble_scan;
    uint32_t last_wifi_scan;
    uint32_t start_time;

    // Thread
    FuriThread* scheduler_thread;
    FuriMutex* mutex;
    volatile bool should_stop;

    // Pause flags
    bool subghz_paused;
    bool ble_paused;
    bool wifi_paused;
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

static void scheduler_wifi_callback(
    const WifiNetworkExtended* network,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.wifi_networks_found++;
    furi_mutex_release(scheduler->mutex);

    if (scheduler->config.wifi_callback) {
        scheduler->config.wifi_callback(&network->base, scheduler->config.callback_context);
    }

    FURI_LOG_I(TAG, "WiFi: %s (%d dBm, ch %d)",
        network->base.ssid[0] ? network->base.ssid : "<hidden>",
        network->base.rssi, network->base.channel);
}

static void scheduler_wifi_deauth_callback(
    const WifiDeauthDetection* deauth,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.wifi_deauths_detected++;
    furi_mutex_release(scheduler->mutex);

    if (scheduler->config.wifi_deauth_callback) {
        scheduler->config.wifi_deauth_callback(
            deauth->bssid,
            deauth->target_mac,
            deauth->reason_code,
            deauth->count,
            scheduler->config.callback_context);
    }

    FURI_LOG_W(TAG, "WiFi deauth detected! BSSID: %02X:%02X:%02X, count: %lu",
        deauth->bssid[3], deauth->bssid[4], deauth->bssid[5], deauth->count);
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
// Radio Source Selection Helper
// ============================================================================

static bool should_use_internal(RadioSourceMode mode, bool external_available) {
    switch (mode) {
    case RadioSourceAuto:
        return !external_available;  // Use internal only if external not available
    case RadioSourceInternal:
        return true;
    case RadioSourceExternal:
        return false;
    case RadioSourceBoth:
        return true;  // Use both
    default:
        return true;
    }
}

static bool should_use_external(RadioSourceMode mode, bool external_available) {
    if (!external_available) return false;

    switch (mode) {
    case RadioSourceAuto:
        return true;  // Prefer external when available
    case RadioSourceInternal:
        return false;
    case RadioSourceExternal:
        return true;
    case RadioSourceBoth:
        return true;  // Use both
    default:
        return false;
    }
}

// ============================================================================
// Scheduler Thread - Main Loop
// ============================================================================

static int32_t scheduler_thread_func(void* context) {
    DetectionScheduler* scheduler = context;

    FURI_LOG_I(TAG, "Detection scheduler started");

    uint32_t last_frequency_hop = 0;
    uint32_t last_ble_scan = 0;
    uint32_t last_wifi_scan = 0;

    // Determine which radios to use based on settings
    bool ext_available = scheduler->external_radio &&
                         external_radio_is_connected(scheduler->external_radio);

    uint32_t ext_caps = ext_available ?
        external_radio_get_capabilities(scheduler->external_radio) : 0;

    bool ext_subghz_available = (ext_caps & EXT_RADIO_CAP_SUBGHZ_RX) != 0;
    bool ext_ble_available = (ext_caps & EXT_RADIO_CAP_BLE_SCAN) != 0;
    bool ext_wifi_available = (ext_caps & EXT_RADIO_CAP_WIFI_SCAN) != 0;

    bool use_internal_subghz = should_use_internal(
        scheduler->config.radio_sources.subghz_source, ext_subghz_available);
    bool use_external_subghz = should_use_external(
        scheduler->config.radio_sources.subghz_source, ext_subghz_available);
    bool use_internal_ble = should_use_internal(
        scheduler->config.radio_sources.ble_source, ext_ble_available);
    bool use_external_ble = should_use_external(
        scheduler->config.radio_sources.ble_source, ext_ble_available);
    bool use_wifi = ext_wifi_available &&
        (scheduler->config.radio_sources.wifi_source != RadioSourceInternal);

    // Update stats with active sources
    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    scheduler->stats.using_internal_subghz = use_internal_subghz;
    scheduler->stats.using_external_subghz = use_external_subghz;
    scheduler->stats.using_internal_ble = use_internal_ble;
    scheduler->stats.using_external_ble = use_external_ble;
    scheduler->stats.using_external_wifi = use_wifi;
    furi_mutex_release(scheduler->mutex);

    FURI_LOG_I(TAG, "Radio sources: SubGHz(int:%d,ext:%d) BLE(int:%d,ext:%d) WiFi(ext:%d)",
        use_internal_subghz, use_external_subghz,
        use_internal_ble, use_external_ble, use_wifi);

    // Start passive scanners (IR and NFC can run alongside everything)
    if (scheduler->config.enable_ir && scheduler->ir) {
        ir_scanner_start(scheduler->ir);
        FURI_LOG_I(TAG, "IR scanner started (passive)");
    }

    if (scheduler->config.enable_nfc && scheduler->nfc) {
        nfc_scanner_start(scheduler->nfc);
        FURI_LOG_I(TAG, "NFC scanner started (passive)");
    }

    // Start internal Sub-GHz at first frequency
    if (scheduler->config.enable_subghz && use_internal_subghz && scheduler->subghz_internal) {
        uint32_t freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
        subghz_scanner_start(scheduler->subghz_internal, freq);
        scheduler->subghz_active = scheduler->subghz_internal;
        FURI_LOG_I(TAG, "Internal Sub-GHz scanner started at %lu Hz", freq);
    }

    // Start WiFi scanner if available
    if (scheduler->config.enable_wifi && use_wifi && scheduler->wifi) {
        WifiScannerConfig wifi_config = {
            .scan_mode = WifiScanModeActive,
            .detect_hidden = true,
            .monitor_probes = scheduler->config.wifi_monitor_probes,
            .detect_deauths = scheduler->config.wifi_detect_deauths,
            .channel = scheduler->config.wifi_channel,
            .dwell_time_ms = 100,
            .rssi_threshold = -90,
            .network_callback = scheduler_wifi_callback,
            .deauth_callback = scheduler_wifi_deauth_callback,
            .callback_context = scheduler,
        };
        wifi_scanner_configure(scheduler->wifi, &wifi_config);
        wifi_scanner_start(scheduler->wifi);
        FURI_LOG_I(TAG, "WiFi scanner started (external ESP32)");
    }

    while (!scheduler->should_stop) {
        uint32_t now = furi_get_tick();

        // ====================================================================
        // Sub-GHz Frequency Hopping (continuous background)
        // ====================================================================
        if (scheduler->config.enable_subghz &&
            !scheduler->subghz_paused &&
            scheduler->config.subghz_continuous) {

            if ((now - last_frequency_hop) >= scheduler->config.subghz_hop_interval_ms) {
                // Hop to next frequency
                scheduler->subghz_frequency_index =
                    (scheduler->subghz_frequency_index + 1) % SUBGHZ_FREQUENCY_COUNT;

                uint32_t new_freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];

                // Hop on internal if active
                if (use_internal_subghz && scheduler->subghz_internal) {
                    subghz_scanner_set_frequency(scheduler->subghz_internal, new_freq);
                }

                // Hop on external if active
                if (use_external_subghz && scheduler->external_radio) {
                    uint8_t freq_cmd[4];
                    freq_cmd[0] = (new_freq >> 24) & 0xFF;
                    freq_cmd[1] = (new_freq >> 16) & 0xFF;
                    freq_cmd[2] = (new_freq >> 8) & 0xFF;
                    freq_cmd[3] = new_freq & 0xFF;
                    external_radio_send_command(
                        scheduler->external_radio,
                        ExtRadioCmdSubGhzSetFreq,
                        freq_cmd, 4);
                }

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
        if (scheduler->config.enable_ble && !scheduler->ble_paused) {
            if ((now - last_ble_scan) >= scheduler->config.ble_scan_interval_ms) {
                // Start internal BLE scan
                if (use_internal_ble && scheduler->ble_internal &&
                    !ble_scanner_is_running(scheduler->ble_internal)) {
                    FURI_LOG_I(TAG, "Starting internal BLE burst scan");
                    ble_scanner_start(scheduler->ble_internal);
                }

                // Start external BLE scan
                if (use_external_ble && scheduler->external_radio) {
                    FURI_LOG_I(TAG, "Starting external BLE burst scan");
                    external_radio_send_command(
                        scheduler->external_radio,
                        ExtRadioCmdBleScanStart,
                        NULL, 0);
                }

                last_ble_scan = now;

                furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                scheduler->stats.ble_scans_completed++;
                furi_mutex_release(scheduler->mutex);
            }
        }

        // ====================================================================
        // WiFi Scanning (via external ESP32)
        // ====================================================================
        if (scheduler->config.enable_wifi &&
            use_wifi &&
            scheduler->wifi &&
            !scheduler->wifi_paused) {

            // WiFi runs continuously via ESP32, just track scan cycles
            if ((now - last_wifi_scan) >= scheduler->config.wifi_scan_interval_ms) {
                last_wifi_scan = now;

                furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                scheduler->stats.wifi_scans_completed++;
                furi_mutex_release(scheduler->mutex);
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

    if (scheduler->subghz_internal && subghz_scanner_is_running(scheduler->subghz_internal)) {
        subghz_scanner_stop(scheduler->subghz_internal);
    }
    if (scheduler->ble_internal && ble_scanner_is_running(scheduler->ble_internal)) {
        ble_scanner_stop(scheduler->ble_internal);
    }
    if (scheduler->wifi && wifi_scanner_is_running(scheduler->wifi)) {
        wifi_scanner_stop(scheduler->wifi);
    }
    if (scheduler->ir && ir_scanner_is_running(scheduler->ir)) {
        ir_scanner_stop(scheduler->ir);
    }
    if (scheduler->nfc && nfc_scanner_is_running(scheduler->nfc)) {
        nfc_scanner_stop(scheduler->nfc);
    }

    // Stop external radio scans
    if (scheduler->external_radio && external_radio_is_connected(scheduler->external_radio)) {
        external_radio_send_command(scheduler->external_radio, ExtRadioCmdSubGhzRxStop, NULL, 0);
        external_radio_send_command(scheduler->external_radio, ExtRadioCmdBleScanStop, NULL, 0);
        external_radio_send_command(scheduler->external_radio, ExtRadioCmdWifiScanStop, NULL, 0);
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
    scheduler->config.enable_wifi = true;
    scheduler->config.enable_ir = true;
    scheduler->config.enable_nfc = true;
    scheduler->config.subghz_hop_interval_ms = SUBGHZ_HOP_INTERVAL_MS;
    scheduler->config.subghz_continuous = true;
    scheduler->config.ble_scan_duration_ms = BLE_SCAN_DURATION_MS;
    scheduler->config.ble_scan_interval_ms = BLE_SCAN_INTERVAL_MS;
    scheduler->config.ble_detect_trackers = true;
    scheduler->config.wifi_scan_interval_ms = 10000;
    scheduler->config.wifi_channel = 0;  // Channel hop
    scheduler->config.wifi_monitor_probes = true;
    scheduler->config.wifi_detect_deauths = true;

    // Default radio sources: Auto (prefer external if available)
    scheduler->config.radio_sources.subghz_source = RadioSourceAuto;
    scheduler->config.radio_sources.ble_source = RadioSourceAuto;
    scheduler->config.radio_sources.wifi_source = RadioSourceExternal;  // No internal WiFi

    // Allocate internal scanners
    scheduler->subghz_internal = subghz_scanner_alloc();
    scheduler->ble_internal = ble_scanner_alloc();
    scheduler->ir = ir_scanner_alloc();
    scheduler->nfc = nfc_scanner_alloc();

    // Configure internal scanner callbacks
    if (scheduler->subghz_internal) {
        SubGhzScannerConfig subghz_config = {
            .detect_replays = true,
            .detect_jamming = true,
            .rssi_threshold = -90,
            .callback = scheduler_subghz_callback,
            .callback_context = scheduler,
        };
        subghz_scanner_configure(scheduler->subghz_internal, &subghz_config);
    }

    if (scheduler->ble_internal) {
        BleScannerConfig ble_config = {
            .detect_trackers = true,
            .detect_spam = true,
            .detect_following = true,
            .rssi_threshold = -85,
            .scan_duration_ms = BLE_SCAN_DURATION_MS,
            .callback = scheduler_ble_callback,
            .callback_context = scheduler,
        };
        ble_scanner_configure(scheduler->ble_internal, &ble_config);
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

    // Free internal scanners
    if (scheduler->subghz_internal) subghz_scanner_free(scheduler->subghz_internal);
    if (scheduler->ble_internal) ble_scanner_free(scheduler->ble_internal);
    if (scheduler->ir) ir_scanner_free(scheduler->ir);
    if (scheduler->nfc) nfc_scanner_free(scheduler->nfc);

    // Free WiFi scanner (external radio manager is not owned by scheduler)
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

void detection_scheduler_set_external_radio(
    DetectionScheduler* scheduler,
    ExternalRadioManager* radio_manager) {

    if (!scheduler) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);

    scheduler->external_radio = radio_manager;

    // Create WiFi scanner if radio supports it
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
    if (!scheduler) return 0;

    // Check internal first
    if (scheduler->subghz_internal) {
        return subghz_scanner_get_frequency(scheduler->subghz_internal);
    }

    // Fall back to frequency table
    return SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];
}

void detection_scheduler_set_frequency(
    DetectionScheduler* scheduler,
    uint32_t frequency) {

    if (!scheduler) return;

    // Set on internal scanner
    if (scheduler->subghz_internal) {
        subghz_scanner_set_frequency(scheduler->subghz_internal, frequency);
    }

    // Set on external scanner
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

void detection_scheduler_set_radio_sources(
    DetectionScheduler* scheduler,
    const RadioSourceSettings* settings) {

    if (!scheduler || !settings) return;

    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
    memcpy(&scheduler->config.radio_sources, settings, sizeof(RadioSourceSettings));
    furi_mutex_release(scheduler->mutex);

    // If running, the changes will take effect on next iteration
    // For immediate effect, would need to stop/restart scanners
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

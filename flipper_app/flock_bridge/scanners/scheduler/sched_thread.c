/**
 * Scheduler Thread
 *
 * Main scheduler thread loop that orchestrates all RF scanning.
 */

#include "sched_internal.h"
#include "../../helpers/external_radio.h"
#include <string.h>

#define TAG "SchedThread"

// ============================================================================
// Radio Source Selection Helpers
// ============================================================================

bool should_use_internal(RadioSourceMode mode, bool external_available) {
    switch (mode) {
    case RadioSourceAuto:
        return !external_available;
    case RadioSourceInternal:
        return true;
    case RadioSourceExternal:
        return false;
    case RadioSourceBoth:
        return true;
    default:
        return true;
    }
}

bool should_use_external(RadioSourceMode mode, bool external_available) {
    if (!external_available) return false;

    switch (mode) {
    case RadioSourceAuto:
        return true;
    case RadioSourceInternal:
        return false;
    case RadioSourceExternal:
        return true;
    case RadioSourceBoth:
        return true;
    default:
        return false;
    }
}

// ============================================================================
// Scheduler Thread - Main Loop
// ============================================================================

int32_t scheduler_thread_func(void* context) {
    DetectionScheduler* scheduler = context;

    FURI_LOG_I(TAG, "Detection scheduler started");

    uint32_t last_frequency_hop = 0;
    uint32_t last_ble_scan = 0;
    uint32_t last_wifi_scan = 0;
    uint32_t last_ir_scan = 0;
    uint32_t last_memory_cleanup = 0;

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

    // Start passive scanners
    if (scheduler->config.enable_nfc && scheduler->nfc) {
        flock_nfc_scanner_start(scheduler->nfc);
        FURI_LOG_I(TAG, "NFC scanner started (passive)");
    }

    // IR Scanner Initialization
    // IR scanning conflicts with USB CDC dual mode due to DMA/timer resource sharing.
    // If USB CDC is active, IR runs in time-multiplexed burst mode (pause USB, scan, resume).
    // If USB CDC is not active (or we're on BT only), IR can run continuously.
    if (scheduler->config.enable_ir && scheduler->ir) {
        bool usb_active = scheduler->usb_cdc && flock_usb_cdc_is_running(scheduler->usb_cdc);

        if (!usb_active) {
            // No USB CDC conflict - start IR scanner continuously
            if (ir_scanner_start(scheduler->ir)) {
                FURI_LOG_I(TAG, "IR scanner started (continuous mode - no USB CDC conflict)");
            } else {
                FURI_LOG_E(TAG, "Failed to start IR scanner");
            }
        } else {
            // USB CDC is active - IR will run in time-multiplexed burst mode
            FURI_LOG_I(TAG, "IR scanner will use time-multiplexed mode (USB CDC active)");
            FURI_LOG_I(TAG, "IR burst scan every %d ms for %d ms",
                IR_SCAN_INTERVAL_MS, IR_SCAN_DURATION_MS);
        }
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
            .network_callback = scheduler_wifi_callback_impl,
            .deauth_callback = scheduler_wifi_deauth_callback_impl,
            .callback_context = scheduler,
        };
        wifi_scanner_configure(scheduler->wifi, &wifi_config);
        wifi_scanner_start(scheduler->wifi);
        FURI_LOG_I(TAG, "WiFi scanner started (external ESP32)");
    }

    while (!scheduler->should_stop) {
        uint32_t now = furi_get_tick();

        // Sub-GHz Frequency Hopping with Decode Protection
        // The hop interval is now 2500ms (was 500ms) to allow complete signal decoding.
        // We also check if a decode is in progress and defer hopping if so.
        if (scheduler->config.enable_subghz &&
            !scheduler->subghz_paused &&
            scheduler->config.subghz_continuous) {

            if ((now - last_frequency_hop) >= scheduler->config.subghz_hop_interval_ms) {
                bool can_hop = true;

                // Check if internal scanner is actively decoding - don't interrupt!
                if (use_internal_subghz && scheduler->subghz_internal) {
                    if (subghz_decoder_is_active(scheduler->subghz_internal)) {
                        // Active decode in progress - defer the hop
                        FURI_LOG_D(TAG, "Deferring frequency hop - decode in progress at %lu Hz",
                            subghz_scanner_get_frequency(scheduler->subghz_internal));
                        can_hop = false;
                    }
                }

                if (can_hop) {
                    scheduler->subghz_frequency_index =
                        (scheduler->subghz_frequency_index + 1) % SUBGHZ_FREQUENCY_COUNT;

                    uint32_t new_freq = SUBGHZ_FREQUENCIES[scheduler->subghz_frequency_index];

                    if (use_internal_subghz && scheduler->subghz_internal) {
                        // When changing frequency, also cycle the preset every full frequency rotation
                        // This gives coverage of OOK and FSK modulations
                        if (scheduler->subghz_frequency_index == 0) {
                            subghz_scanner_cycle_preset(scheduler->subghz_internal);
                            FURI_LOG_I(TAG, "Sub-GHz preset cycled after full frequency rotation");
                        }

                        subghz_scanner_set_frequency(scheduler->subghz_internal, new_freq);
                    }

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

                    FURI_LOG_D(TAG, "Sub-GHz hop to %lu Hz (interval: %lu ms)",
                        new_freq, scheduler->config.subghz_hop_interval_ms);
                }
            }
        }

        // BLE Burst Scanning - Time-multiplexed with BT serial
        if (scheduler->config.enable_ble && !scheduler->ble_paused) {
            if ((now - last_ble_scan) >= scheduler->config.ble_scan_interval_ms) {

                if (use_external_ble && scheduler->external_radio) {
                    FURI_LOG_I(TAG, "Starting external BLE burst scan");
                    external_radio_send_command(
                        scheduler->external_radio,
                        ExtRadioCmdBleScanStart,
                        NULL, 0);
                }

                if (use_internal_ble && scheduler->ble_internal &&
                    !ble_scanner_is_running(scheduler->ble_internal) &&
                    !scheduler->ble_scan_in_progress) {

                    bool can_scan = true;

                    if (scheduler->bt_serial &&
                        flock_bt_serial_is_running(scheduler->bt_serial)) {

                        FURI_LOG_I(TAG, "Pausing BT serial for BLE scan");
                        if (!flock_bt_serial_pause(scheduler->bt_serial)) {
                            FURI_LOG_W(TAG, "Failed to pause BT serial, skipping BLE scan");
                            can_scan = false;
                        }
                    }

                    if (can_scan) {
                        FURI_LOG_I(TAG, "Starting internal BLE burst scan (time-multiplexed)");
                        scheduler->ble_scan_in_progress = true;

                        if (!ble_scanner_start(scheduler->ble_internal)) {
                            FURI_LOG_E(TAG, "Failed to start BLE scan");
                            scheduler->ble_scan_in_progress = false;

                            if (scheduler->bt_serial &&
                                flock_bt_serial_is_paused(scheduler->bt_serial)) {
                                flock_bt_serial_resume(scheduler->bt_serial);
                            }
                        }
                    }
                }

                if (scheduler->ble_scan_in_progress &&
                    scheduler->ble_internal &&
                    !ble_scanner_is_running(scheduler->ble_internal)) {

                    scheduler->ble_scan_in_progress = false;

                    if (scheduler->bt_serial &&
                        flock_bt_serial_is_paused(scheduler->bt_serial)) {
                        FURI_LOG_I(TAG, "Resuming BT serial after BLE scan");
                        if (!flock_bt_serial_resume(scheduler->bt_serial)) {
                            FURI_LOG_E(TAG, "Failed to resume BT serial!");
                        }
                    }

                    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                    scheduler->stats.ble_scans_completed++;
                    furi_mutex_release(scheduler->mutex);
                }

                last_ble_scan = now;
            }
        }

        // WiFi Scanning (via external ESP32)
        if (scheduler->config.enable_wifi &&
            use_wifi &&
            scheduler->wifi &&
            !scheduler->wifi_paused) {

            if ((now - last_wifi_scan) >= scheduler->config.wifi_scan_interval_ms) {
                last_wifi_scan = now;

                furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                scheduler->stats.wifi_scans_completed++;
                furi_mutex_release(scheduler->mutex);
            }
        }

        // IR Burst Scanning - Time-multiplexed with USB CDC
        // Only needed when USB CDC is active; if not, IR runs continuously
        if (scheduler->config.enable_ir && scheduler->ir) {
            bool usb_active = scheduler->usb_cdc && flock_usb_cdc_is_running(scheduler->usb_cdc);
            bool usb_paused = scheduler->usb_cdc && flock_usb_cdc_is_paused(scheduler->usb_cdc);

            // Case 1: IR scan is in progress - check if it should complete
            if (scheduler->ir_scan_in_progress) {
                uint32_t scan_elapsed = now - scheduler->ir_scan_start_time;

                if (scan_elapsed >= IR_SCAN_DURATION_MS) {
                    // IR scan burst complete - stop IR and resume USB
                    FURI_LOG_I(TAG, "IR burst scan complete, stopping IR scanner");
                    ir_scanner_stop(scheduler->ir);
                    scheduler->ir_scan_in_progress = false;

                    // Resume USB CDC if we paused it
                    if (usb_paused) {
                        FURI_LOG_I(TAG, "Resuming USB CDC after IR scan");
                        if (!flock_usb_cdc_resume(scheduler->usb_cdc)) {
                            FURI_LOG_E(TAG, "Failed to resume USB CDC!");
                        }
                    }

                    furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
                    scheduler->stats.ir_signals_captured++;  // Count scan bursts
                    furi_mutex_release(scheduler->mutex);
                }
            }
            // Case 2: USB CDC is active and we need to do time-multiplexed scanning
            else if (usb_active && !scheduler->ir_scan_in_progress) {
                if ((now - last_ir_scan) >= IR_SCAN_INTERVAL_MS) {
                    last_ir_scan = now;

                    FURI_LOG_I(TAG, "Starting IR burst scan (time-multiplexed)");

                    // Pause USB CDC to free DMA/timer resources
                    if (flock_usb_cdc_pause(scheduler->usb_cdc)) {
                        // Start IR scanner
                        if (ir_scanner_start(scheduler->ir)) {
                            scheduler->ir_scan_in_progress = true;
                            scheduler->ir_scan_start_time = now;
                            FURI_LOG_I(TAG, "IR scanner started for burst scan");
                        } else {
                            FURI_LOG_E(TAG, "Failed to start IR scanner, resuming USB");
                            flock_usb_cdc_resume(scheduler->usb_cdc);
                        }
                    } else {
                        FURI_LOG_W(TAG, "Failed to pause USB CDC, skipping IR scan");
                    }
                }
            }
            // Case 3: USB is paused by us and IR is done - shouldn't happen but handle it
            else if (usb_paused && !scheduler->ir_scan_in_progress && !ir_scanner_is_running(scheduler->ir)) {
                FURI_LOG_W(TAG, "USB paused but IR not running - resuming USB");
                flock_usb_cdc_resume(scheduler->usb_cdc);
            }
            // Case 4: No USB CDC active - IR should be running continuously (started at init)
            // If it stopped for some reason, restart it
            else if (!usb_active && !usb_paused && !ir_scanner_is_running(scheduler->ir)) {
                FURI_LOG_I(TAG, "Restarting continuous IR scanner (no USB conflict)");
                ir_scanner_start(scheduler->ir);
            }
        }

        // Periodic Memory Cleanup (now every 60s instead of 10s)
        // This is less aggressive to avoid interrupting signal detection
        if ((now - last_memory_cleanup) >= MEMORY_CLEANUP_INTERVAL_MS) {
            // Only do cleanup if no active decoding is in progress
            bool can_cleanup = true;

            if (scheduler->subghz_internal &&
                subghz_decoder_is_active(scheduler->subghz_internal)) {
                FURI_LOG_D(TAG, "Deferring memory cleanup - Sub-GHz decode in progress");
                can_cleanup = false;
            }

            if (can_cleanup) {
                FURI_LOG_I(TAG, "Performing periodic memory cleanup (interval: %lu ms)",
                    (uint32_t)MEMORY_CLEANUP_INTERVAL_MS);

                // For Sub-GHz, prefer soft reset over full receiver recreation
                // Full recreation is too disruptive - only do it if soft reset fails
                if (scheduler->subghz_internal) {
                    subghz_scanner_reset_decoder(scheduler->subghz_internal);
                    FURI_LOG_D(TAG, "Sub-GHz decoder soft reset complete");
                }

                if (scheduler->nfc && flock_nfc_scanner_is_running(scheduler->nfc)) {
                    flock_nfc_scanner_stop(scheduler->nfc);
                    furi_delay_ms(50);
                    flock_nfc_scanner_start(scheduler->nfc);
                    FURI_LOG_D(TAG, "NFC scanner restarted for memory cleanup");
                }

                last_memory_cleanup = now;
            }
        }

        // Update Stats
        furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
        scheduler->stats.uptime_seconds = (now - scheduler->start_time) / 1000;
        furi_mutex_release(scheduler->mutex);

        furi_delay_ms(SCHEDULER_TICK_MS);
    }

    // Cleanup - Stop all scanners
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
    if (scheduler->nfc && flock_nfc_scanner_is_running(scheduler->nfc)) {
        flock_nfc_scanner_stop(scheduler->nfc);
    }

    // Resume USB CDC if we paused it for IR scanning
    if (scheduler->usb_cdc && flock_usb_cdc_is_paused(scheduler->usb_cdc)) {
        FURI_LOG_I(TAG, "Resuming USB CDC (was paused for IR)");
        flock_usb_cdc_resume(scheduler->usb_cdc);
    }

    // Resume BT serial if we paused it for BLE scanning
    if (scheduler->bt_serial && flock_bt_serial_is_paused(scheduler->bt_serial)) {
        FURI_LOG_I(TAG, "Resuming BT serial (was paused for BLE)");
        flock_bt_serial_resume(scheduler->bt_serial);
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

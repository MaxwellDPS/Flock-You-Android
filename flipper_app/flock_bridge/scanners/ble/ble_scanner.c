#include "ble_scanner_internal.h"
#include <string.h>
#include <stdlib.h>

// ============================================================================
// BLE Scanner Core Module
// ============================================================================
// Handles BLE device detection using the Flipper Zero's available APIs.
//
// IMPLEMENTATION NOTES:
// =====================
// The Flipper Zero SDK does NOT expose BLE scanning (observer mode) to FAPs.
// The internal BLE stack is peripheral-only for external applications.
//
// This scanner uses two approaches:
//
// 1. RF Test Mode (Internal)
//    Uses furi_hal_bt_start_packet_rx() to listen on BLE advertising
//    frequencies (2402, 2426, 2480 MHz). This detects BLE activity via
//    packet reception counts but cannot parse advertisement data.
//    Provides: Activity detection, rough device count estimation.
//    Does NOT provide: MAC addresses, names, manufacturer data.
//
// 2. External Hardware (ESP32/nRF)
//    Full BLE scanning with advertisement parsing via external module.
//    Call ble_scanner_process_advertisement() with data from external hardware.
//    Provides: Full device info, tracker detection, spam detection.
//
// The scanner invokes callbacks when:
// - RF test mode detects BLE activity above threshold (basic detection)
// - External hardware provides parsed advertisement data (full detection)

// ============================================================================
// Channel Frequency Table
// ============================================================================

static const uint16_t BLE_ADV_FREQUENCIES[] = {
    BLE_ADV_FREQ_CH37,  // 2402 MHz
    BLE_ADV_FREQ_CH38,  // 2426 MHz
    BLE_ADV_FREQ_CH39,  // 2480 MHz
};

static const uint8_t BLE_ADV_CHANNELS[] = {
    BLE_ADV_CHANNEL_37,
    BLE_ADV_CHANNEL_38,
    BLE_ADV_CHANNEL_39,
};

#define BLE_CHANNEL_COUNT 3

// ============================================================================
// Helper: Initialize Channel Activity Tracking
// ============================================================================

static void init_channel_activity(BleScanner* scanner) {
    for (int i = 0; i < BLE_CHANNEL_COUNT; i++) {
        scanner->channel_activity[i].frequency_mhz = BLE_ADV_FREQUENCIES[i];
        scanner->channel_activity[i].channel = BLE_ADV_CHANNELS[i];
        scanner->channel_activity[i].sample_count = 0;
        scanner->channel_activity[i].rssi_max = -128;
        scanner->channel_activity[i].rssi_avg = -128;
        scanner->channel_activity[i].activity_count = 0;
        memset(scanner->channel_activity[i].rssi_samples, -128, MAX_RSSI_SAMPLES);
    }
    scanner->current_channel_index = 0;
}

// ============================================================================
// Helper: Record RSSI Sample
// ============================================================================

static void record_rssi_sample(BleChannelActivity* activity, int8_t rssi) {
    if (activity->sample_count < MAX_RSSI_SAMPLES) {
        activity->rssi_samples[activity->sample_count++] = rssi;
    }

    // Update max
    if (rssi > activity->rssi_max) {
        activity->rssi_max = rssi;
    }

    // Update running average
    int32_t sum = 0;
    for (uint8_t i = 0; i < activity->sample_count; i++) {
        sum += activity->rssi_samples[i];
    }
    activity->rssi_avg = (int8_t)(sum / activity->sample_count);
}

// ============================================================================
// Worker Thread - RF Test Mode BLE Detection
// ============================================================================
// Uses the BLE test mode to listen for packets on advertising channels.
// This provides activity detection without advertisement parsing.

static int32_t ble_scanner_worker(void* context) {
    BleScanner* scanner = context;

    FURI_LOG_I(TAG, "BLE scanner worker started (RF test mode)");

    // Check if BLE testing is supported
    if (!furi_hal_bt_is_testing_supported()) {
        FURI_LOG_E(TAG, "BLE testing mode not supported on this firmware!");
        FURI_LOG_E(TAG, "Full BLE scanning requires external hardware (ESP32)");

        furi_mutex_acquire(scanner->mutex, FuriWaitForever);
        scanner->scanning_active = false;
        scanner->running = false;
        furi_mutex_release(scanner->mutex);
        return -1;
    }

    uint32_t elapsed = 0;
    uint32_t dwell_time_ms = 200;  // Time per channel
    uint32_t sample_interval_ms = 20;  // Time between RSSI samples

    init_channel_activity(scanner);

    while (!scanner->should_stop && elapsed < scanner->config.scan_duration_ms) {
        BleChannelActivity* current = &scanner->channel_activity[scanner->current_channel_index];

        // Start packet RX on current BLE advertising channel frequency
        // The frequency needs to be converted to the RF channel index
        // BLE advertising channels: 37=2402MHz, 38=2426MHz, 39=2480MHz
        // RF test mode uses frequency in MHz, converted to channel
        uint8_t rf_channel;
        switch (current->frequency_mhz) {
            case 2402: rf_channel = 0;  break;  // Channel 37 -> RF channel 0
            case 2426: rf_channel = 12; break;  // Channel 38 -> RF channel 12
            case 2480: rf_channel = 39; break;  // Channel 39 -> RF channel 39
            default:   rf_channel = 0;  break;
        }

        // Start receiving packets using BLE test mode
        // Parameters: channel, PHY (1M), modulation index
        furi_hal_bt_start_packet_rx(rf_channel, BLE_PHY_1M);

        furi_mutex_acquire(scanner->mutex, FuriWaitForever);
        scanner->scanning_active = true;
        furi_mutex_release(scanner->mutex);

        // Dwell on channel, taking RSSI samples
        uint32_t dwell_start = furi_get_tick();
        uint32_t packets_before = furi_hal_bt_get_transmitted_packets();

        while (!scanner->should_stop &&
               (furi_get_tick() - dwell_start) < dwell_time_ms) {

            // Get RSSI measurement
            float rssi_float = furi_hal_bt_get_rssi();
            int8_t rssi = (int8_t)rssi_float;

            // Record sample if signal detected
            if (rssi > scanner->config.rssi_threshold) {
                record_rssi_sample(current, rssi);
            }

            furi_delay_ms(sample_interval_ms);
        }

        // Stop packet RX
        furi_hal_bt_stop_packet_test();

        // Check how many packets were received
        uint32_t packets_after = furi_hal_bt_get_transmitted_packets();
        uint32_t packets_received = packets_after - packets_before;

        if (packets_received > 0 || current->rssi_max > scanner->config.rssi_threshold) {
            current->activity_count++;

            furi_mutex_acquire(scanner->mutex, FuriWaitForever);

            FURI_LOG_D(TAG, "Ch%d (%dMHz): pkts=%lu, RSSI max=%d avg=%d",
                current->channel,
                current->frequency_mhz,
                packets_received,
                current->rssi_max,
                current->rssi_avg);

            // Estimate device count based on activity
            // This is a rough heuristic since we can't identify individual devices
            if (packets_received > 0) {
                scanner->stats.total_devices_seen++;

                // If callback is set, report activity detection
                // We create a "synthetic" device entry for the activity
                if (scanner->config.callback) {
                    BleDeviceExtended activity_device = {0};
                    activity_device.base.rssi = current->rssi_max;
                    activity_device.base.is_connectable = false;
                    activity_device.tracker_type = BleTrackerNone;
                    activity_device.spam_type = BleSpamNone;
                    activity_device.last_seen = furi_get_tick();

                    // Mark as unidentified (RF-only detection)
                    snprintf(activity_device.base.name, sizeof(activity_device.base.name),
                        "BLE Activity Ch%d", current->channel);

                    // Generate a pseudo-MAC based on channel and time
                    // This allows tracking activity patterns
                    uint32_t now = furi_get_tick();
                    activity_device.base.mac_address[0] = 0xBE;  // "BE" for BLE
                    activity_device.base.mac_address[1] = 0xAC;  // "AC" for Activity
                    activity_device.base.mac_address[2] = current->channel;
                    activity_device.base.mac_address[3] = (now >> 16) & 0xFF;
                    activity_device.base.mac_address[4] = (now >> 8) & 0xFF;
                    activity_device.base.mac_address[5] = now & 0xFF;

                    scanner->config.callback(&activity_device, scanner->config.callback_context);
                }
            }

            furi_mutex_release(scanner->mutex);
        }

        // Move to next channel
        scanner->current_channel_index = (scanner->current_channel_index + 1) % BLE_CHANNEL_COUNT;
        elapsed += dwell_time_ms;
    }

    // Scan complete - log summary
    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    uint32_t total_activity = 0;
    for (int i = 0; i < BLE_CHANNEL_COUNT; i++) {
        total_activity += scanner->channel_activity[i].activity_count;
        FURI_LOG_I(TAG, "Channel %d summary: activity=%lu, RSSI max=%d",
            scanner->channel_activity[i].channel,
            scanner->channel_activity[i].activity_count,
            scanner->channel_activity[i].rssi_max);
    }

    scanner->scanning_active = false;
    scanner->running = false;
    scanner->stats.scans_completed++;

    FURI_LOG_I(TAG, "BLE scan completed: total_activity=%lu", total_activity);

    if (total_activity == 0) {
        FURI_LOG_I(TAG, "No BLE activity detected. For tracker identification,");
        FURI_LOG_I(TAG, "connect an ESP32 module with BLE scanning firmware.");
    }

    furi_mutex_release(scanner->mutex);

    return 0;
}

// ============================================================================
// Allocation and Cleanup
// ============================================================================

BleScanner* ble_scanner_alloc(void) {
    BleScanner* scanner = malloc(sizeof(BleScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(BleScanner));

    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.detect_trackers = true;
    scanner->config.detect_spam = true;
    scanner->config.detect_following = true;
    scanner->config.rssi_threshold = -85;
    scanner->config.scan_duration_ms = 2000;

    // Open BT service record
    scanner->bt = furi_record_open(RECORD_BT);

    // Initialize channel activity tracking
    init_channel_activity(scanner);

    FURI_LOG_I(TAG, "BLE scanner allocated");
    FURI_LOG_I(TAG, "Note: Full BLE scanning (tracker detection) requires external ESP32");
    return scanner;
}

void ble_scanner_free(BleScanner* scanner) {
    if (!scanner) return;

    ble_scanner_stop(scanner);

    if (scanner->bt) {
        furi_record_close(RECORD_BT);
    }
    if (scanner->mutex) {
        furi_mutex_free(scanner->mutex);
    }

    free(scanner);
    FURI_LOG_I(TAG, "BLE scanner freed");
}

// ============================================================================
// Configuration
// ============================================================================

void ble_scanner_configure(BleScanner* scanner, const BleScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(BleScannerConfig));
    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// Start/Stop Scanning
// ============================================================================

bool ble_scanner_start(BleScanner* scanner) {
    if (!scanner || scanner->running) return false;

    FURI_LOG_I(TAG, "Starting BLE scan (%lu ms)", scanner->config.scan_duration_ms);

    // Check if BT is currently active (connected/advertising)
    scanner->bt_was_active = furi_hal_bt_is_active();
    if (scanner->bt_was_active) {
        FURI_LOG_W(TAG, "BT is active - RF test mode may not work properly");
        FURI_LOG_W(TAG, "Consider pausing BT serial before BLE scanning");
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    // Reset scan state
    scanner->scan_result_count = 0;
    scanner->running = true;
    scanner->should_stop = false;
    scanner->scanning_active = false;
    scanner->scan_start_time = furi_get_tick();

    // Reset channel activity for new scan
    init_channel_activity(scanner);

    furi_mutex_release(scanner->mutex);

    // Start worker thread
    scanner->worker_thread = furi_thread_alloc_ex(
        "BleScanWorker", 2048, ble_scanner_worker, scanner);
    furi_thread_start(scanner->worker_thread);

    FURI_LOG_I(TAG, "BLE scan started (RF test mode)");
    return true;
}

void ble_scanner_stop(BleScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping BLE scan");

    scanner->should_stop = true;

    // Stop RF test mode if active
    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    if (scanner->scanning_active) {
        furi_hal_bt_stop_packet_test();
        scanner->scanning_active = false;
    }
    furi_mutex_release(scanner->mutex);

    // Wait for worker thread
    if (scanner->worker_thread) {
        furi_thread_join(scanner->worker_thread);
        furi_thread_free(scanner->worker_thread);
        scanner->worker_thread = NULL;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->running = false;
    furi_mutex_release(scanner->mutex);

    FURI_LOG_I(TAG, "BLE scan stopped");
}

// ============================================================================
// Status and Statistics
// ============================================================================

bool ble_scanner_is_running(BleScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

void ble_scanner_get_stats(BleScanner* scanner, BleScannerStats* stats) {
    if (!scanner || !stats) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(stats, &scanner->stats, sizeof(BleScannerStats));
    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// External Radio BLE Data Handler
// ============================================================================
// This function is called when external hardware (ESP32/nRF) sends BLE device
// data via the ExtRadioRespBleDevice command.
//
// Expected packet format (after external radio protocol header):
// [0-5]   MAC address (6 bytes)
// [6]     Address type (0=public, 1=random)
// [7]     RSSI (signed int8)
// [8-9]   Advertisement data length (16-bit, big-endian)
// [10+]   Advertisement data (variable length)

void ble_scanner_handle_external_device(
    BleScanner* scanner,
    const uint8_t* data,
    size_t len) {

    if (!scanner || !data) return;

    // Minimum packet: 6 (mac) + 1 (type) + 1 (rssi) + 2 (adv_len) = 10 bytes
    if (len < 10) {
        FURI_LOG_W(TAG, "External BLE device packet too short: %zu bytes", len);
        return;
    }

    // Parse packet
    const uint8_t* mac = data;
    uint8_t address_type = data[6];
    int8_t rssi = (int8_t)data[7];
    uint16_t adv_data_len = (data[8] << 8) | data[9];

    // Validate advertisement data length
    if ((size_t)(10 + adv_data_len) > len) {
        FURI_LOG_W(TAG, "External BLE packet truncated: adv_len=%u, available=%zu",
            adv_data_len, len - 10);
        adv_data_len = len - 10;  // Use what we have
    }

    const uint8_t* adv_data = (adv_data_len > 0) ? &data[10] : NULL;

    FURI_LOG_D(TAG, "External BLE device: %02X:%02X:%02X:%02X:%02X:%02X RSSI=%d adv_len=%u",
        mac[0], mac[1], mac[2], mac[3], mac[4], mac[5], rssi, adv_data_len);

    // Process through the tracker detection pipeline
    ble_scanner_process_advertisement(
        scanner,
        mac,
        address_type,
        rssi,
        adv_data,
        adv_data_len);
}

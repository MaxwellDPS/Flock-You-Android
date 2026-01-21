/**
 * Scheduler Callbacks
 *
 * Internal callbacks that forward scanner detections to user-provided callbacks.
 */

#include "sched_internal.h"
#include "../helpers/external_radio.h"

#define TAG "SchedCallbacks"

// These callbacks are internal - forward to user-provided callbacks

void scheduler_subghz_callback_impl(
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

void scheduler_ble_callback_impl(
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

void scheduler_wifi_callback_impl(
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

void scheduler_wifi_deauth_callback_impl(
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

void scheduler_ir_callback_impl(
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

void scheduler_nfc_callback_impl(
    const FlockNfcDetectionExtended* detection,
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
// External Radio Data Callback
// ============================================================================
// This callback routes external radio responses to the appropriate scanner.
// It is registered with the ExternalRadioManager when the scheduler starts.

void scheduler_external_radio_callback(
    uint8_t cmd,
    const uint8_t* data,
    size_t len,
    void* context) {

    DetectionScheduler* scheduler = context;
    if (!scheduler || !scheduler->running) return;

    switch (cmd) {
    case ExtRadioRespBleDevice:
        // Route BLE device data to the BLE scanner
        if (scheduler->ble_internal) {
            ble_scanner_handle_external_device(scheduler->ble_internal, data, len);
        }
        break;

    case ExtRadioRespBleScanDone:
        // External BLE scan completed
        FURI_LOG_I(TAG, "External BLE scan completed");
        furi_mutex_acquire(scheduler->mutex, FuriWaitForever);
        scheduler->stats.ble_scans_completed++;
        furi_mutex_release(scheduler->mutex);
        break;

    // WiFi responses are handled by wifi_scanner's own callback
    // via the wifi_scanner_internal structure

    default:
        // Unknown or unhandled response - might be for another module
        FURI_LOG_D(TAG, "Unhandled external radio response: 0x%02X", cmd);
        break;
    }
}

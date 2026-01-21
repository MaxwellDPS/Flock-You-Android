/**
 * Detection Callbacks
 *
 * Handles callbacks from the detection scheduler for various RF detections.
 * Each callback serializes the detection and sends it to the connected device.
 */

#include "handlers.h"
#include "../protocol/flock_protocol.h"
#include <furi.h>

#define TAG "FlockDetection"

void on_subghz_detection(const FlockSubGhzDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->subghz_detection_count++;

    // Build and send Sub-GHz result
    FlockSubGhzScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .frequency_start = detection->frequency,
        .frequency_end = detection->frequency,
        .detection_count = 1,
    };
    memcpy(&result.detections[0], detection, sizeof(FlockSubGhzDetection));

    size_t len = flock_protocol_serialize_subghz_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_ble_detection(const FlockBleDevice* device, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->ble_scan_count++;

    // Build and send BLE result
    FlockBleScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .device_count = 1,
    };
    memcpy(&result.devices[0], device, sizeof(FlockBleDevice));

    // Serialize and send BLE detection to connected device
    size_t len = flock_protocol_serialize_ble_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_ir_detection(const FlockIrDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->ir_detection_count++;

    // Build and send IR result
    FlockIrScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .detection_count = 1,
    };
    memcpy(&result.detections[0], detection, sizeof(FlockIrDetection));

    // Serialize and send IR detection to connected device
    size_t len = flock_protocol_serialize_ir_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_nfc_detection(const FlockNfcDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->nfc_detection_count++;

    // Build and send NFC result
    FlockNfcScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .detection_count = 1,
    };
    memcpy(&result.detections[0], detection, sizeof(FlockNfcDetection));

    // Serialize and send NFC detection to connected device
    size_t len = flock_protocol_serialize_nfc_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

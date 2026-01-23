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
    if (!app || !detection) return;

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

void on_subghz_scan_status(const FlockSubGhzScanStatus* status, void* context) {
    FlockBridgeApp* app = context;
    if (!app || !status) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    // Serialize and send scan status to connected device
    size_t len = flock_protocol_serialize_subghz_status(status, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_ble_detection(const FlockBleDevice* device, void* context) {
    FlockBridgeApp* app = context;
    if (!app || !device) return;

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

void on_wifi_detection(const FlockWifiNetwork* network, void* context) {
    FlockBridgeApp* app = context;
    if (!app || !network) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->wifi_scan_count++;

    // Build and send WiFi result
    FlockWifiScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .network_count = 1,
    };
    memcpy(&result.networks[0], network, sizeof(FlockWifiNetwork));

    // Serialize and send WiFi detection to connected device
    size_t len = flock_protocol_serialize_wifi_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_wifi_deauth(const uint8_t* bssid, const uint8_t* target, uint8_t reason, uint32_t count, void* context) {
    UNUSED(reason);
    FlockBridgeApp* app = context;
    if (!app || !bssid || !target) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    // Build and send WIPS alert for deauth attack
    FlockWipsAlert alert = {0};
    alert.timestamp = furi_get_tick() / 1000;
    alert.alert_type = WipsAlertDeauthAttack;
    alert.severity = WipsSeverityHigh;
    alert.bssid_count = 2;
    memcpy(alert.bssids[0], bssid, 6);
    memcpy(alert.bssids[1], target, 6);
    snprintf(alert.description, sizeof(alert.description), "Deauth attack detected (%lu frames)", (unsigned long)count);

    // Serialize and send WIPS alert
    size_t len = flock_protocol_serialize_wips_alert(&alert, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_ir_detection(const FlockIrDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app || !detection) return;

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
    if (!app || !detection) return;

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

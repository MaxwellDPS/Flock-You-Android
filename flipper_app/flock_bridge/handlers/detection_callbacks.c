/**
 * Detection Callbacks
 *
 * Handles callbacks from the detection scheduler for various RF detections.
 * Each callback serializes the detection and sends it to the connected device.
 *
 * NOTE: Uses lightweight single-detection serializers to avoid stack overflow.
 * The full result structs (e.g., FlockSubGhzScanResult with 16 detections)
 * are too large for Flipper's limited stack (~4KB per thread).
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

    // Use lightweight single-detection serializer (avoids ~500 byte stack alloc)
    uint32_t timestamp = furi_get_tick() / 1000;
    size_t len = flock_protocol_serialize_single_subghz(
        timestamp, detection, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

void on_subghz_scan_status(const FlockSubGhzScanStatus* status, void* context) {
    FlockBridgeApp* app = context;
    if (!app || !status) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    // FlockSubGhzScanStatus is small (~28 bytes), safe on stack
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

    // Use lightweight single-detection serializer (avoids ~1KB stack alloc)
    uint32_t timestamp = furi_get_tick() / 1000;
    size_t len = flock_protocol_serialize_single_ble(
        timestamp, device, app->tx_buffer, sizeof(app->tx_buffer));
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

    // Use lightweight single-detection serializer (avoids ~1.4KB stack alloc)
    uint32_t timestamp = furi_get_tick() / 1000;
    size_t len = flock_protocol_serialize_single_wifi(
        timestamp, network, app->tx_buffer, sizeof(app->tx_buffer));
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

    // FlockWipsAlert is ~150 bytes, acceptable on stack
    FlockWipsAlert alert = {0};
    alert.timestamp = furi_get_tick() / 1000;
    alert.alert_type = WipsAlertDeauthAttack;
    alert.severity = WipsSeverityHigh;
    alert.bssid_count = 2;
    memcpy(alert.bssids[0], bssid, 6);
    memcpy(alert.bssids[1], target, 6);
    snprintf(alert.description, sizeof(alert.description), "Deauth attack detected (%lu frames)", (unsigned long)count);

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

    // Use lightweight single-detection serializer
    uint32_t timestamp = furi_get_tick() / 1000;
    size_t len = flock_protocol_serialize_single_ir(
        timestamp, detection, app->tx_buffer, sizeof(app->tx_buffer));
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

    // Use lightweight single-detection serializer
    uint32_t timestamp = furi_get_tick() / 1000;
    size_t len = flock_protocol_serialize_single_nfc(
        timestamp, detection, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

/**
 * BLE Active Scan Probe
 *
 * Configures BLE scanning mode (active/passive) for device discovery.
 */

#include "../probes.h"
#include "../../../helpers/external_radio.h"
#include "../../../scanners/scheduler/detection_scheduler.h"
#include <furi.h>

#define TAG "ProbeBLE"

void handle_ble_active_scan(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockBleActiveScanPayload ble_payload;
    if (!flock_protocol_parse_ble_active_scan(buffer, length, &ble_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid BLE scan parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "BLE Active Scan: %s",
        ble_payload.active_mode ? "enabled" : "disabled");

    // Try external radio first (ESP32/nRF with full BLE stack)
    if (app->external_radio && external_radio_is_connected(app->external_radio)) {
        uint32_t caps = external_radio_get_capabilities(app->external_radio);
        if (caps & EXT_RADIO_CAP_BLE_SCAN) {
            uint8_t cmd_data[1] = { ble_payload.active_mode };
            if (external_radio_send_command(
                    app->external_radio,
                    ble_payload.active_mode ? ExtRadioCmdBleScanStart : ExtRadioCmdBleScanStop,
                    cmd_data, 1)) {
                FURI_LOG_I(TAG, "BLE Active Scan: configured via external radio");
                notification_message(app->notifications, &sequence_blink_blue_100);

                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
                return;
            }
        }
    }

    // Fall back to internal BLE scanner if available
    if (app->detection_scheduler) {
        // Configure the internal BLE scanner active mode via scheduler
        // Note: Internal Flipper BLE can only do passive scanning in RF test mode
        // Active scanning requires BT stack which conflicts with BT serial
        FURI_LOG_W(TAG, "BLE Active Scan: Internal radio limited to passive mode");

        if (ble_payload.active_mode) {
            // Trigger a BLE scan burst
            detection_scheduler_pause_ble(app->detection_scheduler, false);
        } else {
            detection_scheduler_pause_ble(app->detection_scheduler, true);
        }

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // No BLE capability available
    FURI_LOG_E(TAG, "BLE Active Scan: No BLE radio available");
    size_t len = flock_protocol_create_error(
        FLOCK_ERR_HARDWARE_FAIL, "No BLE radio available",
        app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }
}

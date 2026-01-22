/**
 * IR Strobe Probe
 *
 * Infrared strobe pattern generation for IR device testing and research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_infrared.h>

#define TAG "ProbeIR"

void handle_ir_strobe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockIrStrobePayload ir_payload;
    if (!flock_protocol_parse_ir_strobe(buffer, length, &ir_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid IR strobe parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "IR Strobe TX: %u Hz, %u%% duty, %u ms",
        ir_payload.frequency_hz, ir_payload.duty_cycle, ir_payload.duration_ms);

    // Validate parameters
    if (ir_payload.frequency_hz < 1 || ir_payload.frequency_hz > 100 ||
        ir_payload.duty_cycle < 1 || ir_payload.duty_cycle > 100 ||
        ir_payload.duration_ms < 100 || ir_payload.duration_ms > 30000) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "IR strobe params out of range",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Calculate timing parameters
    uint32_t period_ms = 1000 / ir_payload.frequency_hz;
    uint32_t on_time_ms = (period_ms * ir_payload.duty_cycle) / 100;
    uint32_t off_time_ms = period_ms - on_time_ms;
    uint32_t total_cycles = ir_payload.duration_ms / period_ms;

    if (on_time_ms < 1) on_time_ms = 1;
    if (off_time_ms < 1) off_time_ms = 1;

    FURI_LOG_I(TAG, "IR Strobe: %lu cycles, %lu ms on, %lu ms off",
        total_cycles, on_time_ms, off_time_ms);

    // Flipper IR uses 38kHz carrier modulation
    // We strobe by sending bursts of 38kHz carrier
    const uint32_t IR_CARRIER_FREQ = 38000;
    const float IR_DUTY_CYCLE = 0.33f;

    notification_message(app->notifications, &sequence_blink_red_10);

    // Generate strobe pattern
    for (uint32_t cycle = 0; cycle < total_cycles; cycle++) {
        // Note: For a true strobe, we want visible/near-IR light
        // Flipper's IR LED is at 940nm (invisible), but this tests the concept
        // For visible strobe testing, an external LED array would be needed

        // Toggle carrier for strobe effect
        furi_hal_infrared_async_tx_start(IR_CARRIER_FREQ, IR_DUTY_CYCLE);
        furi_delay_ms(on_time_ms);
        furi_hal_infrared_async_tx_stop();
        furi_delay_ms(off_time_ms);

        // Periodic LED feedback
        if (cycle % 10 == 0) {
            notification_message(app->notifications, &sequence_blink_red_10);
        }
    }

    // Ensure IR is stopped
    furi_hal_infrared_async_tx_stop();

    FURI_LOG_I(TAG, "IR Strobe TX complete: %lu cycles at %u Hz",
        total_cycles, ir_payload.frequency_hz);
    notification_message(app->notifications, &sequence_blink_red_100);

    // Send success response
    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }
}

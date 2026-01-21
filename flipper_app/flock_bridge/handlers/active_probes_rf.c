/**
 * Active Probes - RF-based
 *
 * RF and infrared test/emulation functions for security research.
 * Includes: LF probe (125kHz TPMS), IR strobe, Sub-GHz replay.
 */

#include "handlers.h"
#include <furi.h>
#include <furi_hal_subghz.h>
#include <furi_hal_rfid.h>
#include <furi_hal_infrared.h>

#define TAG "FlockProbesRF"

// ============================================================================
// LF Probe (Tire Kicker) - 125kHz TPMS wake
// ============================================================================

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

// ============================================================================
// IR Strobe (Opticom Verifier) - Traffic preemption test
// ============================================================================

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
        // Turn on IR LED with carrier
        furi_hal_infrared_async_tx_set_signal(
            InfraredTxSetSignalSilence);  // Prepare TX

        // Send carrier burst for on_time
        // We use raw IR timing - send a long mark (carrier on)
        uint32_t timings[2];
        timings[0] = on_time_ms * 1000;  // Mark duration in microseconds
        timings[1] = off_time_ms * 1000; // Space duration in microseconds

        // Note: For a true strobe, we want visible/near-IR light
        // Flipper's IR LED is at 940nm (invisible), but this tests the concept
        // For visible strobe testing, an external LED array would be needed

        // Simple approach: toggle carrier for strobe effect
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

// ============================================================================
// Sub-GHz Replay (Sleep Denial)
// ============================================================================

void handle_subghz_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockSubGhzReplayPayload replay_payload;
    if (flock_protocol_parse_subghz_replay(buffer, length, &replay_payload)) {
        FURI_LOG_I(TAG, "SubGHz Replay TX: %lu Hz, %u bytes, %u repeats",
            replay_payload.frequency, replay_payload.data_len, replay_payload.repeat_count);

        if (replay_payload.data_len == 0 || replay_payload.data_len > 256 ||
            replay_payload.repeat_count < 1 || replay_payload.repeat_count > 10) {
            size_t len = flock_protocol_create_error(
                FLOCK_ERR_INVALID_PARAM, "SubGHz replay params invalid",
                app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            return;
        }

        // Validate frequency is in allowed Sub-GHz bands
        bool freq_valid = false;
        if ((replay_payload.frequency >= 300000000 && replay_payload.frequency <= 348000000) ||
            (replay_payload.frequency >= 387000000 && replay_payload.frequency <= 464000000) ||
            (replay_payload.frequency >= 779000000 && replay_payload.frequency <= 928000000)) {
            freq_valid = true;
        }
        if (!freq_valid) {
            FURI_LOG_E(TAG, "SubGHz: frequency %lu not in allowed bands", replay_payload.frequency);
            size_t len = flock_protocol_create_error(
                FLOCK_ERR_INVALID_PARAM, "Frequency not in allowed bands",
                app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            return;
        }

        FURI_LOG_I(TAG, "SubGHz: initializing for %lu Hz", replay_payload.frequency);

        // Initialize Sub-GHz radio
        furi_hal_subghz_reset();
        furi_hal_subghz_idle();
        furi_hal_subghz_set_frequency_and_path(replay_payload.frequency);

        // Transmit the raw signal data
        for (uint8_t repeat = 0; repeat < replay_payload.repeat_count; repeat++) {
            FURI_LOG_D(TAG, "SubGHz: TX repeat %u/%u", repeat + 1, replay_payload.repeat_count);

            bool tx_level = true;
            for (uint16_t i = 0; i + 1 < replay_payload.data_len; i += 2) {
                uint16_t duration_us = (replay_payload.data[i] << 8) | replay_payload.data[i + 1];

                if (duration_us > 0 && duration_us < 50000) {
                    if (tx_level) {
                        furi_hal_subghz_tx();
                        furi_delay_us(duration_us);
                    } else {
                        furi_hal_subghz_idle();
                        furi_delay_us(duration_us);
                    }
                    tx_level = !tx_level;
                }
            }

            furi_hal_subghz_idle();

            if (repeat < replay_payload.repeat_count - 1) {
                furi_delay_ms(50);
            }
        }

        furi_hal_subghz_sleep();

        FURI_LOG_I(TAG, "SubGHz Replay TX complete");

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
    } else {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid SubGHz replay parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
    }
}

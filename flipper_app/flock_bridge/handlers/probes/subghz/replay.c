/**
 * Sub-GHz Replay Probe
 *
 * Replays captured Sub-GHz signals for security research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_subghz.h>

#define TAG "ProbeSubGhz"

void handle_subghz_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockSubGhzReplayPayload replay_payload;
    if (!flock_protocol_parse_subghz_replay(buffer, length, &replay_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid SubGHz replay parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

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
}

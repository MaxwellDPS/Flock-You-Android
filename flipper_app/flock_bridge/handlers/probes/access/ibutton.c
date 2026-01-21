/**
 * iButton Emulation Probe
 *
 * Dallas 1-Wire iButton emulation for access control research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_ibutton.h>
#include <string.h>

#define TAG "ProbeIButton"

void handle_ibutton_emulate(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockIButtonPayload ibutton_payload;
    if (!flock_protocol_parse_ibutton(buffer, length, &ibutton_payload)) {
        size_t len = flock_protocol_create_error(FLOCK_ERR_INVALID_PARAM,
            "Invalid iButton parameters", app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
        return;
    }

    FURI_LOG_I(TAG, "iButton Emulate: %02X:%02X:%02X:%02X:%02X:%02X:%02X:%02X",
        ibutton_payload.key_id[0], ibutton_payload.key_id[1], ibutton_payload.key_id[2],
        ibutton_payload.key_id[3], ibutton_payload.key_id[4], ibutton_payload.key_id[5],
        ibutton_payload.key_id[6], ibutton_payload.key_id[7]);

    // Validate family code
    uint8_t family_code = ibutton_payload.key_id[0];
    if (family_code != 0x01 && family_code != 0x02 && family_code != 0x08) {
        FURI_LOG_W(TAG, "iButton: unusual family code 0x%02X", family_code);
    }

    // Calculate and validate CRC8 (Dallas 1-Wire CRC)
    uint8_t crc = 0;
    for (int i = 0; i < 7; i++) {
        uint8_t byte = ibutton_payload.key_id[i];
        for (int j = 0; j < 8; j++) {
            uint8_t mix = (crc ^ byte) & 0x01;
            crc >>= 1;
            if (mix) crc ^= 0x8C;
            byte >>= 1;
        }
    }
    if (crc != ibutton_payload.key_id[7]) {
        FURI_LOG_W(TAG, "iButton: CRC mismatch (calc=0x%02X, provided=0x%02X)",
            crc, ibutton_payload.key_id[7]);
    }

    // Emulation duration
    const uint32_t EMULATE_DURATION_MS = 10000;
    FURI_LOG_I(TAG, "iButton: starting emulation for %lu ms", EMULATE_DURATION_MS);

    // Store key data for emulation
    static uint8_t s_ibutton_key[8];
    memcpy(s_ibutton_key, ibutton_payload.key_id, 8);

    // Configure 1-Wire pin
    furi_hal_ibutton_pin_configure();

    uint32_t start_tick = furi_get_tick();
    uint32_t end_tick = start_tick + furi_ms_to_ticks(EMULATE_DURATION_MS);
    uint32_t contacts_detected = 0;

    // Emulation loop
    while (furi_get_tick() < end_tick) {
        furi_hal_ibutton_pin_write(true);
        furi_delay_us(10);

        // Respond to reset pulses periodically
        if ((furi_get_tick() - start_tick) % furi_ms_to_ticks(500) < furi_ms_to_ticks(10)) {
            // Presence pulse
            furi_hal_ibutton_pin_write(false);
            furi_delay_us(120);
            furi_hal_ibutton_pin_write(true);
            furi_delay_us(300);

            // Send 64 bits of ROM data (LSB first)
            for (int byte_idx = 0; byte_idx < 8; byte_idx++) {
                uint8_t byte_val = s_ibutton_key[byte_idx];
                for (int bit_idx = 0; bit_idx < 8; bit_idx++) {
                    if (byte_val & (1 << bit_idx)) {
                        // Write 1: short low, long high
                        furi_hal_ibutton_pin_write(false);
                        furi_delay_us(6);
                        furi_hal_ibutton_pin_write(true);
                        furi_delay_us(64);
                    } else {
                        // Write 0: long low, short high
                        furi_hal_ibutton_pin_write(false);
                        furi_delay_us(60);
                        furi_hal_ibutton_pin_write(true);
                        furi_delay_us(10);
                    }
                }
            }
            contacts_detected++;
        }
        furi_delay_ms(1);
    }

    furi_hal_ibutton_pin_write(true);
    FURI_LOG_I(TAG, "iButton: emulation complete, %lu cycles", contacts_detected);

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
}

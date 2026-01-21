/**
 * Active Probes - Physical Access
 *
 * Physical access test/emulation functions for security research.
 * Includes: Wiegand replay, MagSpoof, iButton emulation.
 */

#include "handlers.h"
#include <furi.h>
#include <furi_hal_gpio.h>
#include <furi_hal_ibutton.h>

#define TAG "FlockProbesAccess"

// ============================================================================
// Wiegand Replay (Replay Injector)
// ============================================================================

void handle_wiegand_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockWiegandReplayPayload wiegand_payload;
    if (!flock_protocol_parse_wiegand_replay(buffer, length, &wiegand_payload)) {
        size_t len = flock_protocol_create_error(FLOCK_ERR_INVALID_PARAM,
            "Invalid Wiegand parameters", app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
        return;
    }

    FURI_LOG_I(TAG, "Wiegand Replay TX: FC=%lu, CN=%lu, %u-bit",
        wiegand_payload.facility_code, wiegand_payload.card_number, wiegand_payload.bit_length);

    if (wiegand_payload.bit_length < 26 || wiegand_payload.bit_length > 48 ||
        wiegand_payload.facility_code > 0xFFFF || wiegand_payload.card_number > 0xFFFFFF) {
        size_t len = flock_protocol_create_error(FLOCK_ERR_INVALID_PARAM,
            "Wiegand params out of range", app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
        return;
    }

    const uint32_t PULSE_WIDTH_US = 50, PULSE_INTERVAL_US = 2000;

    furi_hal_gpio_init(&gpio_ext_pc0, GpioModeOutputPushPull, GpioPullUp, GpioSpeedVeryHigh);
    furi_hal_gpio_init(&gpio_ext_pc1, GpioModeOutputPushPull, GpioPullUp, GpioSpeedVeryHigh);
    furi_hal_gpio_write(&gpio_ext_pc0, true);
    furi_hal_gpio_write(&gpio_ext_pc1, true);

    uint64_t wiegand_data = 0;
    uint8_t parity_even = 0, parity_odd = 0;

    if (wiegand_payload.bit_length == 26) {
        uint8_t fc = wiegand_payload.facility_code & 0xFF;
        uint16_t cn = wiegand_payload.card_number & 0xFFFF;
        wiegand_data = ((uint64_t)fc << 17) | ((uint64_t)cn << 1);
        for (int i = 1; i <= 12; i++)
            if (wiegand_data & (1ULL << (25 - i))) parity_even++;
        for (int i = 13; i <= 24; i++)
            if (wiegand_data & (1ULL << (25 - i))) parity_odd++;
        if (parity_even % 2) wiegand_data |= (1ULL << 25);
        if (!(parity_odd % 2)) wiegand_data |= 1ULL;
    }

    FURI_LOG_I(TAG, "Wiegand: sending %u bits, data=0x%llX", wiegand_payload.bit_length, wiegand_data);

    for (int bit = wiegand_payload.bit_length - 1; bit >= 0; bit--) {
        const GpioPin* pin = (wiegand_data & (1ULL << bit)) ? &gpio_ext_pc1 : &gpio_ext_pc0;
        furi_hal_gpio_write(pin, false);
        furi_delay_us(PULSE_WIDTH_US);
        furi_hal_gpio_write(pin, true);
        furi_delay_us(PULSE_INTERVAL_US);
    }

    furi_hal_gpio_init(&gpio_ext_pc0, GpioModeAnalog, GpioPullNo, GpioSpeedLow);
    furi_hal_gpio_init(&gpio_ext_pc1, GpioModeAnalog, GpioPullNo, GpioSpeedLow);
    FURI_LOG_I(TAG, "Wiegand Replay TX complete");

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
}

// ============================================================================
// MagSpoof - Magnetic stripe emulation
// ============================================================================

void handle_magspoof_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockMagSpoofPayload mag_payload;
    if (!flock_protocol_parse_magspoof(buffer, length, &mag_payload)) {
        size_t len = flock_protocol_create_error(FLOCK_ERR_INVALID_PARAM,
            "Invalid MagSpoof parameters", app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
        return;
    }

    FURI_LOG_I(TAG, "MagSpoof TX: T1=%u bytes, T2=%u bytes", mag_payload.track1_len, mag_payload.track2_len);

    if ((mag_payload.track1_len == 0 && mag_payload.track2_len == 0) ||
        mag_payload.track1_len > 79 || mag_payload.track2_len > 40) {
        size_t len = flock_protocol_create_error(FLOCK_ERR_INVALID_PARAM,
            "MagSpoof track data invalid", app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
        return;
    }

    const uint32_t TRACK2_HALF_PERIOD_US = 1111;  // ~450 Hz F2F encoding
    furi_hal_gpio_init(&gpio_ext_pc3, GpioModeOutputPushPull, GpioPullNo, GpioSpeedVeryHigh);
    FURI_LOG_I(TAG, "MagSpoof: starting transmission");

    #define MAGSPOOF_BIT(hp, bv) do { \
        if (bv) { \
            furi_hal_gpio_write(&gpio_ext_pc3, true); furi_delay_us(hp); \
            furi_hal_gpio_write(&gpio_ext_pc3, false); furi_delay_us(hp); \
        } else { \
            furi_hal_gpio_write(&gpio_ext_pc3, true); furi_delay_us(hp/2); \
            furi_hal_gpio_write(&gpio_ext_pc3, false); furi_delay_us(hp/2); \
            furi_hal_gpio_write(&gpio_ext_pc3, true); furi_delay_us(hp/2); \
            furi_hal_gpio_write(&gpio_ext_pc3, false); furi_delay_us(hp/2); \
        } \
    } while(0)

    for (int i = 0; i < 25; i++) MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);

    if (mag_payload.track2_len > 0) {
        // Start sentinel: 11010
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1); MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1); MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);

        for (uint8_t i = 0; i < mag_payload.track2_len; i++) {
            uint8_t ch = (uint8_t)mag_payload.track2[i], parity = 1;
            for (int bit = 0; bit < 4; bit++) {
                uint8_t b = (ch >> bit) & 1;
                MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, b);
                parity ^= b;
            }
            MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, parity);
        }

        // End sentinel: 11111
        for (int i = 0; i < 5; i++) MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
    }
    #undef MAGSPOOF_BIT

    for (int i = 0; i < 25; i++) {
        furi_hal_gpio_write(&gpio_ext_pc3, false);
        furi_delay_us(TRACK2_HALF_PERIOD_US);
    }

    furi_hal_gpio_init(&gpio_ext_pc3, GpioModeAnalog, GpioPullNo, GpioSpeedLow);
    FURI_LOG_I(TAG, "MagSpoof TX complete");

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
}

// ============================================================================
// iButton Emulate (Master Key)
// ============================================================================

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

    uint8_t family_code = ibutton_payload.key_id[0];
    if (family_code != 0x01 && family_code != 0x02 && family_code != 0x08)
        FURI_LOG_W(TAG, "iButton: unusual family code 0x%02X", family_code);

    // Calculate and validate CRC8
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
    if (crc != ibutton_payload.key_id[7])
        FURI_LOG_W(TAG, "iButton: CRC mismatch (calc=0x%02X, provided=0x%02X)", crc, ibutton_payload.key_id[7]);

    const uint32_t EMULATE_DURATION_MS = 10000;
    FURI_LOG_I(TAG, "iButton: starting emulation for %lu ms", EMULATE_DURATION_MS);

    static uint8_t s_ibutton_key[8];
    memcpy(s_ibutton_key, ibutton_payload.key_id, 8);
    furi_hal_ibutton_pin_configure();

    uint32_t start_tick = furi_get_tick();
    uint32_t end_tick = start_tick + furi_ms_to_ticks(EMULATE_DURATION_MS);
    uint32_t contacts_detected = 0;

    while (furi_get_tick() < end_tick) {
        furi_hal_ibutton_pin_write(true);
        furi_delay_us(10);

        if ((furi_get_tick() - start_tick) % furi_ms_to_ticks(500) < furi_ms_to_ticks(10)) {
            // Presence pulse
            furi_hal_ibutton_pin_write(false); furi_delay_us(120);
            furi_hal_ibutton_pin_write(true); furi_delay_us(300);

            // Send 64 bits of ROM data
            for (int byte_idx = 0; byte_idx < 8; byte_idx++) {
                uint8_t byte_val = s_ibutton_key[byte_idx];
                for (int bit_idx = 0; bit_idx < 8; bit_idx++) {
                    if (byte_val & (1 << bit_idx)) {
                        furi_hal_ibutton_pin_write(false); furi_delay_us(6);
                        furi_hal_ibutton_pin_write(true); furi_delay_us(64);
                    } else {
                        furi_hal_ibutton_pin_write(false); furi_delay_us(60);
                        furi_hal_ibutton_pin_write(true); furi_delay_us(10);
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

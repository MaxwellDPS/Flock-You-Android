/**
 * MagSpoof Probe
 *
 * Magnetic stripe emulation for magstripe reader research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_gpio.h>

#define TAG "ProbeMagSpoof"

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

    // Track 2 timing: ~450 Hz F2F encoding
    const uint32_t TRACK2_HALF_PERIOD_US = 1111;

    // Configure coil driver GPIO
    furi_hal_gpio_init(&gpio_ext_pc3, GpioModeOutputPushPull, GpioPullNo, GpioSpeedVeryHigh);
    FURI_LOG_I(TAG, "MagSpoof: starting transmission");

    // Macro for F2F bit encoding
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

    // Leading zeros
    for (int i = 0; i < 25; i++) MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);

    // Track 2 data
    if (mag_payload.track2_len > 0) {
        // Start sentinel: 11010 (;)
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);

        // Data characters (5 bits each: 4 data + 1 parity)
        for (uint8_t i = 0; i < mag_payload.track2_len; i++) {
            uint8_t ch = (uint8_t)mag_payload.track2[i];
            uint8_t parity = 1;
            for (int bit = 0; bit < 4; bit++) {
                uint8_t b = (ch >> bit) & 1;
                MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, b);
                parity ^= b;
            }
            MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, parity);
        }

        // End sentinel: 11111 (?)
        for (int i = 0; i < 5; i++) MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
    }

    #undef MAGSPOOF_BIT

    // Trailing zeros
    for (int i = 0; i < 25; i++) {
        furi_hal_gpio_write(&gpio_ext_pc3, false);
        furi_delay_us(TRACK2_HALF_PERIOD_US);
    }

    // Reset GPIO
    furi_hal_gpio_init(&gpio_ext_pc3, GpioModeAnalog, GpioPullNo, GpioSpeedLow);
    FURI_LOG_I(TAG, "MagSpoof TX complete");

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
}

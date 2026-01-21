/**
 * Wiegand Replay Probe
 *
 * Wiegand protocol transmission for access control research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_gpio.h>

#define TAG "ProbeWiegand"

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

    // Configure Wiegand data lines (D0 = PC0, D1 = PC1)
    furi_hal_gpio_init(&gpio_ext_pc0, GpioModeOutputPushPull, GpioPullUp, GpioSpeedVeryHigh);
    furi_hal_gpio_init(&gpio_ext_pc1, GpioModeOutputPushPull, GpioPullUp, GpioSpeedVeryHigh);
    furi_hal_gpio_write(&gpio_ext_pc0, true);
    furi_hal_gpio_write(&gpio_ext_pc1, true);

    uint64_t wiegand_data = 0;
    uint8_t parity_even = 0, parity_odd = 0;

    // Build Wiegand-26 frame
    if (wiegand_payload.bit_length == 26) {
        uint8_t fc = wiegand_payload.facility_code & 0xFF;
        uint16_t cn = wiegand_payload.card_number & 0xFFFF;
        wiegand_data = ((uint64_t)fc << 17) | ((uint64_t)cn << 1);

        // Calculate even parity for bits 1-12
        for (int i = 1; i <= 12; i++)
            if (wiegand_data & (1ULL << (25 - i))) parity_even++;

        // Calculate odd parity for bits 13-24
        for (int i = 13; i <= 24; i++)
            if (wiegand_data & (1ULL << (25 - i))) parity_odd++;

        if (parity_even % 2) wiegand_data |= (1ULL << 25);
        if (!(parity_odd % 2)) wiegand_data |= 1ULL;
    }

    FURI_LOG_I(TAG, "Wiegand: sending %u bits, data=0x%llX", wiegand_payload.bit_length, wiegand_data);

    // Transmit Wiegand data
    for (int bit = wiegand_payload.bit_length - 1; bit >= 0; bit--) {
        const GpioPin* pin = (wiegand_data & (1ULL << bit)) ? &gpio_ext_pc1 : &gpio_ext_pc0;
        furi_hal_gpio_write(pin, false);
        furi_delay_us(PULSE_WIDTH_US);
        furi_hal_gpio_write(pin, true);
        furi_delay_us(PULSE_INTERVAL_US);
    }

    // Reset GPIO to high-impedance
    furi_hal_gpio_init(&gpio_ext_pc0, GpioModeAnalog, GpioPullNo, GpioSpeedLow);
    furi_hal_gpio_init(&gpio_ext_pc1, GpioModeAnalog, GpioPullNo, GpioSpeedLow);

    FURI_LOG_I(TAG, "Wiegand Replay TX complete");

    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) flock_bridge_send_data(app, app->tx_buffer, len);
}

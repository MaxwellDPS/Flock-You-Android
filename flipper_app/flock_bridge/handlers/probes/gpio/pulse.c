/**
 * GPIO Pulse Probe
 *
 * GPIO pulse generation for inductive loop detection research.
 */

#include "../probes.h"
#include <furi.h>
#include <furi_hal_gpio.h>

#define TAG "ProbeGPIO"

void handle_gpio_pulse_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length) {
    notification_message(app->notifications, &sequence_blink_magenta_10);
    notification_message(app->notifications, &sequence_blink_magenta_10);

    FlockGpioPulsePayload gpio_payload;
    if (!flock_protocol_parse_gpio_pulse(buffer, length, &gpio_payload)) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "Invalid GPIO pulse parameters",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    FURI_LOG_I(TAG, "GPIO Pulse TX: %lu Hz, %u ms, %u pulses",
        gpio_payload.frequency_hz, gpio_payload.duration_ms, gpio_payload.pulse_count);

    // Validate parameters for safety
    if (gpio_payload.frequency_hz < 1 || gpio_payload.frequency_hz > 500000 ||
        gpio_payload.duration_ms < 10 || gpio_payload.duration_ms > 10000 ||
        gpio_payload.pulse_count < 1 || gpio_payload.pulse_count > 1000) {
        size_t len = flock_protocol_create_error(
            FLOCK_ERR_INVALID_PARAM, "GPIO pulse params out of range",
            app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        return;
    }

    // Calculate pulse timing
    uint32_t half_period_us = 500000 / gpio_payload.frequency_hz;
    uint32_t total_pulses = (gpio_payload.duration_ms * gpio_payload.frequency_hz) / 1000;
    if (total_pulses > gpio_payload.pulse_count && gpio_payload.pulse_count > 0) {
        total_pulses = gpio_payload.pulse_count;
    }

    // Configure GPIO pin C3 as output
    furi_hal_gpio_init(&gpio_ext_pc3, GpioModeOutputPushPull, GpioPullNo, GpioSpeedVeryHigh);

    FURI_LOG_I(TAG, "GPIO Pulse: generating %lu pulses at %lu Hz", total_pulses, gpio_payload.frequency_hz);

    // Generate pulse train
    for (uint32_t i = 0; i < total_pulses; i++) {
        furi_hal_gpio_write(&gpio_ext_pc3, true);
        furi_delay_us(half_period_us);
        furi_hal_gpio_write(&gpio_ext_pc3, false);
        furi_delay_us(half_period_us);
    }

    // Reset GPIO to input (high-impedance)
    furi_hal_gpio_init(&gpio_ext_pc3, GpioModeAnalog, GpioPullNo, GpioSpeedLow);

    FURI_LOG_I(TAG, "GPIO Pulse TX complete");

    // Send success response
    size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }
}

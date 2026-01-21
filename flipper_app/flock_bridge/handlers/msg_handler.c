/**
 * Message Handler
 *
 * Processes incoming protocol messages from USB CDC or Bluetooth.
 * Handles buffering, header parsing, validation, and dispatch.
 */

#include "handlers.h"
#include <furi.h>
#include <furi_hal_power.h>

#define TAG "FlockMsgHandler"

// Buffer timeout in ticks - discard partial data waiting too long
#define RX_BUFFER_TIMEOUT_MS 50

// ============================================================================
// Message Type Validation
// ============================================================================

static bool is_valid_message_type(uint8_t type) {
    switch (type) {
    case FlockMsgTypeHeartbeat:
    case FlockMsgTypeWifiScanRequest:
    case FlockMsgTypeWifiScanResult:
    case FlockMsgTypeSubGhzScanRequest:
    case FlockMsgTypeSubGhzScanResult:
    case FlockMsgTypeStatusRequest:
    case FlockMsgTypeStatusResponse:
    case FlockMsgTypeWipsAlert:
    case FlockMsgTypeBleScanRequest:
    case FlockMsgTypeBleScanResult:
    case FlockMsgTypeIrScanRequest:
    case FlockMsgTypeIrScanResult:
    case FlockMsgTypeNfcScanRequest:
    case FlockMsgTypeNfcScanResult:
    case FlockMsgTypeLfProbeTx:
    case FlockMsgTypeIrStrobeTx:
    case FlockMsgTypeWifiProbeTx:
    case FlockMsgTypeBleActiveScan:
    case FlockMsgTypeZigbeeBeaconTx:
    case FlockMsgTypeGpioPulseTx:
    case FlockMsgTypeSubGhzReplayTx:
    case FlockMsgTypeWiegandReplayTx:
    case FlockMsgTypeMagSpoofTx:
    case FlockMsgTypeIButtonEmulate:
    case FlockMsgTypeNrf24InjectTx:
    case FlockMsgTypeSubGhzConfig:
    case FlockMsgTypeIrConfig:
    case FlockMsgTypeNrf24Config:
    case FlockMsgTypeError:
        return true;
    default:
        return false;
    }
}

// ============================================================================
// Message Dispatch - Routes messages to appropriate handlers
// ============================================================================

static void dispatch_message(FlockBridgeApp* app, FlockMessageHeader* header,
                             const uint8_t* buffer, size_t buffer_len) {
    // Rate limiting for responses
    static uint32_t last_response_tick = 0;
    const uint32_t MIN_RESPONSE_INTERVAL_MS = 5;
    uint32_t current_tick = furi_get_tick();
    bool should_respond = (current_tick - last_response_tick) >= furi_ms_to_ticks(MIN_RESPONSE_INTERVAL_MS);

    switch (header->type) {
    case FlockMsgTypeHeartbeat: {
        if (should_respond) {
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
                last_response_tick = current_tick;
            }
        }
        break;
    }

    case FlockMsgTypeStatusRequest: {
        FlockStatusResponse status = {
            .protocol_version = FLOCK_PROTOCOL_VERSION,
            .wifi_board_connected = app->wifi_board_connected,
            .subghz_ready = app->subghz_ready,
            .ble_ready = app->ble_ready,
            .ir_ready = app->ir_ready,
            .nfc_ready = app->nfc_ready,
            .battery_percent = furi_hal_power_get_pct(),
            .uptime_seconds = (furi_get_tick() - app->uptime_start) / 1000,
            .wifi_scan_count = (uint16_t)app->wifi_scan_count,
            .subghz_detection_count = (uint16_t)app->subghz_detection_count,
            .ble_scan_count = (uint16_t)app->ble_scan_count,
            .ir_detection_count = (uint16_t)app->ir_detection_count,
            .nfc_detection_count = (uint16_t)app->nfc_detection_count,
            .wips_alert_count = (uint16_t)app->wips_alert_count,
        };
        size_t len = flock_protocol_serialize_status(&status, app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        break;
    }

    case FlockMsgTypeWifiScanRequest: {
        FURI_LOG_I(TAG, "WiFi scan requested");
        notification_message(app->notifications, &sequence_blink_blue_10);

        if (app->external_radio) {
            FURI_LOG_I(TAG, "WiFi scan forwarded to external radio");
        }

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        break;
    }

    case FlockMsgTypeSubGhzScanRequest: {
        FURI_LOG_I(TAG, "Sub-GHz scan requested");
        notification_message(app->notifications, &sequence_blink_yellow_10);

        if (app->detection_scheduler) {
            FURI_LOG_I(TAG, "SubGHz scanner active");
        }

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        break;
    }

    case FlockMsgTypeBleScanRequest: {
        FURI_LOG_I(TAG, "BLE scan requested");
        notification_message(app->notifications, &sequence_blink_cyan_10);

        if (app->detection_scheduler) {
            FURI_LOG_I(TAG, "BLE scanner active");
        }

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        break;
    }

    case FlockMsgTypeIrScanRequest: {
        FURI_LOG_I(TAG, "IR scan requested (passive mode only)");
        notification_message(app->notifications, &sequence_blink_red_10);

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        break;
    }

    case FlockMsgTypeNfcScanRequest: {
        FURI_LOG_I(TAG, "NFC scan requested");
        notification_message(app->notifications, &sequence_blink_green_10);

        if (app->detection_scheduler) {
            FURI_LOG_I(TAG, "NFC scanner active");
        }

        size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
        if (len > 0) {
            flock_bridge_send_data(app, app->tx_buffer, len);
        }
        break;
    }

    // Active Probe Commands - dispatch to active_probes.c
    case FlockMsgTypeLfProbeTx:
        handle_lf_probe_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeIrStrobeTx:
        handle_ir_strobe_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeWifiProbeTx:
        handle_wifi_probe_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeBleActiveScan:
        handle_ble_active_scan(app, buffer, buffer_len);
        break;

    case FlockMsgTypeZigbeeBeaconTx:
        handle_zigbee_beacon_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeGpioPulseTx:
        handle_gpio_pulse_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeSubGhzReplayTx:
        handle_subghz_replay_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeWiegandReplayTx:
        handle_wiegand_replay_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeMagSpoofTx:
        handle_magspoof_tx(app, buffer, buffer_len);
        break;

    case FlockMsgTypeIButtonEmulate:
        handle_ibutton_emulate(app, buffer, buffer_len);
        break;

    case FlockMsgTypeNrf24InjectTx:
        handle_nrf24_inject_tx(app, buffer, buffer_len);
        break;

    // Passive Scan Configuration
    case FlockMsgTypeSubGhzConfig: {
        FlockSubGhzConfigPayload config_payload;
        if (flock_protocol_parse_subghz_config(buffer, buffer_len, &config_payload)) {
            FURI_LOG_I(TAG, "SubGHz Config: type=%u, freq=%lu, mod=%u",
                config_payload.probe_type, config_payload.frequency, config_payload.modulation);
        }
        break;
    }

    case FlockMsgTypeIrConfig:
        FURI_LOG_I(TAG, "IR Config requested");
        break;

    case FlockMsgTypeNrf24Config:
        FURI_LOG_I(TAG, "NRF24 Config requested");
        break;

    default:
        FURI_LOG_W(TAG, "Unknown message type: 0x%02X", header->type);
        break;
    }
}

// ============================================================================
// Data Received Callback
// ============================================================================

void flock_bridge_data_received(void* context, uint8_t* data, size_t length) {
    FlockBridgeApp* app = context;
    if (!app || !data || length == 0) return;

    FURI_LOG_I(TAG, "Data callback: %zu bytes received", length);

    // Single blink on data receive
    notification_message(app->notifications, &sequence_blink_blue_10);

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    uint32_t now = furi_get_tick();

    // Check for stale partial buffer data
    if (app->rx_buffer_len > 0 && app->rx_buffer_timestamp > 0) {
        uint32_t elapsed = now - app->rx_buffer_timestamp;
        if (elapsed > furi_ms_to_ticks(RX_BUFFER_TIMEOUT_MS)) {
            FURI_LOG_W(TAG, "RX buffer timeout: discarding %zu stale bytes", app->rx_buffer_len);
            app->rx_buffer_len = 0;
        }
    }

    // Append to rx_buffer with overflow protection
    size_t space = sizeof(app->rx_buffer) - app->rx_buffer_len;
    size_t to_copy = length < space ? length : space;

    if (to_copy < length) {
        FURI_LOG_W(TAG, "RX buffer overflow: dropping %zu bytes (buffer full)", length - to_copy);
    }

    if (to_copy > 0) {
        memcpy(app->rx_buffer + app->rx_buffer_len, data, to_copy);
        app->rx_buffer_len += to_copy;
        app->rx_buffer_timestamp = now;
    }

    // Process complete messages
    size_t resync_attempts = 0;
    const size_t max_resync = 64;

    while (app->rx_buffer_len >= FLOCK_HEADER_SIZE) {
        FlockMessageHeader header;
        if (!flock_protocol_parse_header(app->rx_buffer, app->rx_buffer_len, &header)) {
            // Invalid header, discard first byte and try again
            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
            resync_attempts++;

            if (resync_attempts >= max_resync) {
                FURI_LOG_W(TAG, "Resync failed after %zu bytes, clearing buffer", resync_attempts);
                app->rx_buffer_len = 0;
                app->rx_buffer_timestamp = 0;
                break;
            }
            continue;
        }

        // Validate payload length
        if (header.payload_length > FLOCK_MAX_PAYLOAD_SIZE) {
            FURI_LOG_E(TAG, "Payload too large: %u > %u", header.payload_length, FLOCK_MAX_PAYLOAD_SIZE);

            size_t err_len = flock_protocol_create_error(
                FLOCK_ERR_INVALID_MSG, "Payload exceeds max size",
                app->tx_buffer, sizeof(app->tx_buffer));
            if (err_len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, err_len);
            }

            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
            resync_attempts++;

            if (resync_attempts >= max_resync) {
                FURI_LOG_W(TAG, "Resync failed after %zu bytes, clearing buffer", resync_attempts);
                app->rx_buffer_len = 0;
                app->rx_buffer_timestamp = 0;
                break;
            }
            continue;
        }

        // Validate message type
        if (!is_valid_message_type(header.type)) {
            FURI_LOG_W(TAG, "Unknown message type: 0x%02X, discarding", header.type);
            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
            resync_attempts++;

            if (resync_attempts >= max_resync) {
                FURI_LOG_W(TAG, "Resync failed after %zu bytes, clearing buffer", resync_attempts);
                app->rx_buffer_len = 0;
                app->rx_buffer_timestamp = 0;
                break;
            }
            continue;
        }

        // Check if complete message is available
        size_t msg_size = (size_t)FLOCK_HEADER_SIZE + (size_t)header.payload_length;

        if (app->rx_buffer_len < msg_size) {
            // Wait for more data
            break;
        }

        // Reset resync counter on valid message
        resync_attempts = 0;

        // Handle message
        app->messages_received++;
        dispatch_message(app, &header, app->rx_buffer, app->rx_buffer_len);

        // Remove processed message from buffer
        memmove(app->rx_buffer, app->rx_buffer + msg_size, app->rx_buffer_len - msg_size);
        app->rx_buffer_len -= msg_size;

        if (app->rx_buffer_len == 0) {
            app->rx_buffer_timestamp = 0;
        }
    }

    furi_mutex_release(app->mutex);
}

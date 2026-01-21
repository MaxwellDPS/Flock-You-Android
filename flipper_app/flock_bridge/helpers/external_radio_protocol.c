#include "external_radio_internal.h"

// Serial Callback - UART Protocol RX State Machine

void external_radio_serial_callback(
    FuriHalSerialHandle* handle,
    FuriHalSerialRxEvent event,
    void* context) {

    ExternalRadioManager* manager = context;
    UNUSED(handle);

    if (event == FuriHalSerialRxEventData) {
        uint8_t byte = furi_hal_serial_async_rx(manager->serial);
        manager->last_rx_time = furi_get_tick();

        // Parse incoming data using state machine
        switch (manager->rx_state) {
        case RxStateWaitStart:
            if (byte == EXT_RADIO_START_BYTE) {
                manager->rx_state = RxStateLenHigh;
                manager->rx_crc = 0;
            }
            break;

        case RxStateLenHigh:
            manager->rx_payload_len = (uint16_t)byte << 8;
            manager->rx_crc ^= byte;
            manager->rx_state = RxStateLenLow;
            break;

        case RxStateLenLow:
            manager->rx_payload_len |= byte;
            manager->rx_crc ^= byte;
            if (manager->rx_payload_len > EXT_RADIO_MAX_PAYLOAD) {
                FURI_LOG_W(TAG, "Payload too large: %d", manager->rx_payload_len);
                manager->rx_state = RxStateWaitStart;
            } else {
                manager->rx_state = RxStateCmd;
            }
            break;

        case RxStateCmd:
            manager->rx_cmd = byte;
            manager->rx_crc ^= byte;
            manager->rx_payload_idx = 0;
            if (manager->rx_payload_len > 0) {
                manager->rx_state = RxStatePayload;
            } else {
                manager->rx_state = RxStateCrc;
            }
            break;

        case RxStatePayload:
            // Bounds check to prevent buffer overflow (defensive programming)
            if (manager->rx_payload_idx >= EXT_RADIO_MAX_PAYLOAD) {
                FURI_LOG_E(TAG, "Payload index overflow: %d", manager->rx_payload_idx);
                manager->rx_state = RxStateWaitStart;
                break;
            }
            manager->rx_buffer[manager->rx_payload_idx++] = byte;
            manager->rx_crc ^= byte;
            if (manager->rx_payload_idx >= manager->rx_payload_len) {
                manager->rx_state = RxStateCrc;
            }
            break;

        case RxStateCrc:
            if (byte == manager->rx_crc) {
                // Valid packet received
                // Handle in worker thread via flag/queue
                if (manager->sync_waiting && manager->sync_response_buf) {
                    // Copy response for sync command
                    size_t copy_len = manager->rx_payload_len;
                    if (copy_len > manager->sync_response_max) {
                        copy_len = manager->sync_response_max;
                    }
                    memcpy(manager->sync_response_buf, manager->rx_buffer, copy_len);
                    if (manager->sync_response_len) {
                        *manager->sync_response_len = copy_len;
                    }
                    manager->sync_waiting = false;
                    furi_semaphore_release(manager->response_sem);
                } else if (manager->config.on_data) {
                    // Async callback
                    manager->config.on_data(
                        manager->rx_cmd,
                        manager->rx_buffer,
                        manager->rx_payload_len,
                        manager->config.callback_context);
                }
            } else {
                FURI_LOG_W(TAG, "CRC mismatch: got 0x%02X, expected 0x%02X",
                    byte, manager->rx_crc);
            }
            manager->rx_state = RxStateWaitStart;
            break;
        }
    }
}

// Worker Thread

int32_t external_radio_worker(void* context) {
    ExternalRadioManager* manager = context;

    FURI_LOG_I(TAG, "External radio worker started");

    // Allow radio to boot
    furi_delay_ms(100);

    // Detection phase - try to ping the external radio
    bool detected = false;
    const int max_ping_attempts = 3;
    const uint32_t ping_wait_ms = EXT_RADIO_TIMEOUT_MS;

    for (int attempt = 0; attempt < max_ping_attempts && !manager->should_stop && !detected; attempt++) {
        FURI_LOG_I(TAG, "Attempting to detect external radio (attempt %d/%d)", attempt + 1, max_ping_attempts);

        // Send ping command
        if (!external_radio_send_command(manager, ExtRadioCmdPing, NULL, 0)) {
            FURI_LOG_W(TAG, "Failed to send ping command on attempt %d", attempt + 1);
            furi_delay_ms(100);  // Brief delay before retry
            continue;
        }

        // Wait for response
        uint32_t ping_timeout = furi_get_tick() + ping_wait_ms;
        while (!manager->should_stop && furi_get_tick() < ping_timeout) {
            furi_delay_ms(10);

            // Check if we got a response (state changes in serial callback)
            if (manager->state == ExternalRadioStateConnected) {
                detected = true;
                FURI_LOG_I(TAG, "External radio detected on attempt %d", attempt + 1);
                break;
            }
        }

        if (!detected && !manager->should_stop) {
            FURI_LOG_D(TAG, "No response on attempt %d, retrying...", attempt + 1);
        }
    }

    // Handle detection result
    if (!detected) {
        if (!manager->should_stop) {
            FURI_LOG_I(TAG, "No external radio detected after %d attempts - operating without external radio", max_ping_attempts);
            manager->state = ExternalRadioStateDisconnected;
            manager->detected_type = ExternalRadioNone;

            // Notify callers that no radio is available (graceful fallback)
            if (manager->config.on_disconnect) {
                manager->config.on_disconnect(manager->config.callback_context);
            }
        }
    } else {
        // Radio detected - request detailed info
        FURI_LOG_I(TAG, "Requesting external radio info...");
        uint8_t info_buf[sizeof(ExtRadioInfo)];
        size_t info_len = 0;

        if (external_radio_send_command_sync(
            manager, ExtRadioCmdGetInfo, NULL, 0,
            info_buf, &info_len, EXT_RADIO_TIMEOUT_MS)) {

            if (info_len >= sizeof(ExtRadioInfo)) {
                memcpy(&manager->radio_info, info_buf, sizeof(ExtRadioInfo));
                manager->detected_type = manager->radio_info.type;

                FURI_LOG_I(TAG, "External radio info: %s v%d.%d.%d (caps: 0x%08lX)",
                    manager->radio_info.name,
                    manager->radio_info.version_major,
                    manager->radio_info.version_minor,
                    manager->radio_info.version_patch,
                    (unsigned long)manager->radio_info.capabilities);

                if (manager->config.on_connect) {
                    manager->config.on_connect(
                        manager->detected_type,
                        manager->config.callback_context);
                }
            } else {
                FURI_LOG_W(TAG, "Got short info response (%zu bytes, expected %zu)",
                    info_len, sizeof(ExtRadioInfo));
            }
        } else {
            FURI_LOG_W(TAG, "Failed to get radio info, but radio is connected");
            // Radio is connected but didn't give info - still usable
            if (manager->config.on_connect) {
                manager->config.on_connect(
                    ExternalRadioNone,  // Type unknown
                    manager->config.callback_context);
            }
        }
    }

    // Main loop - heartbeat and monitor
    uint32_t consecutive_failures = 0;
    const uint32_t max_consecutive_failures = 3;

    while (!manager->should_stop) {
        uint32_t now = furi_get_tick();

        // Only send heartbeats if we're connected
        if (manager->state == ExternalRadioStateConnected) {
            // Send periodic heartbeat
            if ((now - manager->last_heartbeat) >= EXT_RADIO_HEARTBEAT_MS) {
                if (external_radio_send_command(manager, ExtRadioCmdPing, NULL, 0)) {
                    manager->last_heartbeat = now;
                    FURI_LOG_D(TAG, "Sent heartbeat ping");
                } else {
                    consecutive_failures++;
                    FURI_LOG_W(TAG, "Failed to send heartbeat (%lu consecutive failures)",
                        (unsigned long)consecutive_failures);
                }
            }

            // Check for timeout (no response in too long)
            uint32_t time_since_rx = now - manager->last_rx_time;
            if (time_since_rx > (EXT_RADIO_HEARTBEAT_MS * 3)) {
                FURI_LOG_W(TAG, "External radio timeout (no RX for %lu ms), disconnecting",
                    (unsigned long)time_since_rx);
                manager->state = ExternalRadioStateDisconnected;
                manager->detected_type = ExternalRadioNone;
                consecutive_failures = 0;

                if (manager->config.on_disconnect) {
                    manager->config.on_disconnect(manager->config.callback_context);
                }
            }

            // Disconnect after too many consecutive send failures
            if (consecutive_failures >= max_consecutive_failures) {
                FURI_LOG_E(TAG, "Too many consecutive failures, disconnecting");
                manager->state = ExternalRadioStateDisconnected;
                manager->detected_type = ExternalRadioNone;
                consecutive_failures = 0;

                if (manager->config.on_disconnect) {
                    manager->config.on_disconnect(manager->config.callback_context);
                }
            }
        } else {
            // Not connected - try to reconnect periodically
            static uint32_t last_reconnect_attempt = 0;
            const uint32_t reconnect_interval_ms = 10000;  // Try every 10 seconds

            if ((now - last_reconnect_attempt) >= reconnect_interval_ms) {
                FURI_LOG_D(TAG, "Attempting to reconnect to external radio...");
                last_reconnect_attempt = now;

                if (external_radio_send_command(manager, ExtRadioCmdPing, NULL, 0)) {
                    // Ping sent, response will be handled by serial callback
                    manager->state = ExternalRadioStateConnecting;
                }
            }

            // Check if reconnection succeeded (state changed by serial callback)
            if (manager->state == ExternalRadioStateConnected) {
                FURI_LOG_I(TAG, "External radio reconnected");
                consecutive_failures = 0;

                if (manager->config.on_connect) {
                    manager->config.on_connect(
                        manager->detected_type,
                        manager->config.callback_context);
                }
            }
        }

        furi_delay_ms(100);
    }

    FURI_LOG_I(TAG, "External radio worker stopped");
    return 0;
}

// Command Serialization and Transmission

bool external_radio_send_command(
    ExternalRadioManager* manager,
    ExtRadioCommand cmd,
    const uint8_t* payload,
    size_t payload_len) {

    // Validate manager and check if running
    if (!manager) {
        FURI_LOG_W(TAG, "send_command: manager is NULL");
        return false;
    }

    if (!manager->running) {
        FURI_LOG_W(TAG, "send_command: manager not running");
        return false;
    }

    if (!manager->serial_active || !manager->serial) {
        FURI_LOG_W(TAG, "send_command: serial not active");
        return false;
    }

    if (payload_len > EXT_RADIO_MAX_PAYLOAD) {
        FURI_LOG_W(TAG, "send_command: payload too large (%zu > %d)", payload_len, EXT_RADIO_MAX_PAYLOAD);
        return false;
    }

    // Build packet: [START][LEN_H][LEN_L][CMD][PAYLOAD...][CRC8]
    uint8_t header[4];
    header[0] = EXT_RADIO_START_BYTE;
    header[1] = (payload_len >> 8) & 0xFF;
    header[2] = payload_len & 0xFF;
    header[3] = cmd;

    // Calculate CRC (XOR of LEN_H, LEN_L, CMD, and payload)
    uint8_t crc = header[1] ^ header[2] ^ header[3];
    for (size_t i = 0; i < payload_len; i++) {
        crc ^= payload[i];
    }

    // Acquire mutex with timeout to prevent deadlock
    FuriStatus status = furi_mutex_acquire(manager->mutex, 500);
    if (status != FuriStatusOk) {
        FURI_LOG_E(TAG, "send_command: failed to acquire mutex (timeout)");
        return false;
    }

    // Double-check serial is still active after acquiring mutex
    if (!manager->serial_active || !manager->serial) {
        furi_mutex_release(manager->mutex);
        FURI_LOG_W(TAG, "send_command: serial became inactive");
        return false;
    }

    // Send header
    furi_hal_serial_tx(manager->serial, header, 4);

    // Send payload
    if (payload && payload_len > 0) {
        furi_hal_serial_tx(manager->serial, payload, payload_len);
    }

    // Send CRC
    furi_hal_serial_tx(manager->serial, &crc, 1);

    furi_mutex_release(manager->mutex);

    FURI_LOG_D(TAG, "Sent command 0x%02X with %zu bytes payload", cmd, payload_len);
    return true;
}

bool external_radio_send_command_sync(
    ExternalRadioManager* manager,
    ExtRadioCommand cmd,
    const uint8_t* payload,
    size_t payload_len,
    uint8_t* response,
    size_t* response_len,
    uint32_t timeout_ms) {

    // Validate manager
    if (!manager) {
        FURI_LOG_W(TAG, "send_command_sync: manager is NULL");
        return false;
    }

    // Check if manager is running
    if (!manager->running) {
        FURI_LOG_W(TAG, "send_command_sync: manager not running");
        return false;
    }

    // Check if semaphore is available
    if (!manager->response_sem) {
        FURI_LOG_E(TAG, "send_command_sync: response semaphore is NULL");
        return false;
    }

    // Setup sync response buffer with mutex protection
    FuriStatus mutex_status = furi_mutex_acquire(manager->mutex, 500);
    if (mutex_status != FuriStatusOk) {
        FURI_LOG_E(TAG, "send_command_sync: failed to acquire mutex");
        return false;
    }

    manager->sync_response_buf = response;
    manager->sync_response_len = response_len;
    manager->sync_response_max = response ? EXT_RADIO_MAX_PAYLOAD : 0;
    manager->sync_waiting = true;

    // Initialize response_len to 0 in case of failure
    if (response_len) {
        *response_len = 0;
    }

    furi_mutex_release(manager->mutex);

    // Send command
    if (!external_radio_send_command(manager, cmd, payload, payload_len)) {
        FURI_LOG_W(TAG, "send_command_sync: failed to send command 0x%02X", cmd);
        manager->sync_waiting = false;
        manager->sync_response_buf = NULL;
        manager->sync_response_len = NULL;
        return false;
    }

    // Wait for response with timeout
    FuriStatus status = furi_semaphore_acquire(manager->response_sem, timeout_ms);

    // Clean up sync state
    furi_mutex_acquire(manager->mutex, FuriWaitForever);
    manager->sync_waiting = false;
    manager->sync_response_buf = NULL;
    manager->sync_response_len = NULL;
    furi_mutex_release(manager->mutex);

    if (status == FuriStatusOk) {
        // Response received - update state on ACK
        if (cmd == ExtRadioCmdPing) {
            manager->state = ExternalRadioStateConnected;
            FURI_LOG_I(TAG, "External radio connected (ping successful)");
        }
        return true;
    } else if (status == FuriStatusErrorTimeout) {
        FURI_LOG_W(TAG, "send_command_sync: timeout waiting for response to 0x%02X", cmd);
        // Mark as error state if we were expecting a response
        if (manager->state == ExternalRadioStateConnected) {
            // Don't immediately disconnect on single timeout, let the worker handle it
            FURI_LOG_D(TAG, "Single timeout, letting worker thread handle disconnect logic");
        }
    } else {
        FURI_LOG_E(TAG, "send_command_sync: semaphore error %d", status);
    }

    return false;
}

// Utility Functions

const char* external_radio_get_type_name(ExternalRadioType type) {
    switch (type) {
    case ExternalRadioESP32: return "ESP32";
    case ExternalRadioESP8266: return "ESP8266";
    case ExternalRadioCC1101: return "CC1101";
    case ExternalRadioNRF24: return "nRF24L01+";
    case ExternalRadioCC2500: return "CC2500";
    case ExternalRadioSX1276: return "SX1276/LoRa";
    default: return "Unknown";
    }
}

uint8_t external_radio_calc_crc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
    }
    return crc;
}
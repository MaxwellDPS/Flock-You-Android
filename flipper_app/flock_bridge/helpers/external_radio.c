#include "external_radio.h"
#include <string.h>
#include <stdlib.h>

#define TAG "ExternalRadio"

// ============================================================================
// Internal Structures
// ============================================================================

struct ExternalRadioManager {
    ExternalRadioConfig config;
    ExternalRadioState state;
    ExternalRadioType detected_type;
    ExtRadioInfo radio_info;

    // Serial
    FuriHalSerialHandle* serial;
    bool serial_active;

    // RX state machine
    enum {
        RxStateWaitStart,
        RxStateLenHigh,
        RxStateLenLow,
        RxStateCmd,
        RxStatePayload,
        RxStateCrc,
    } rx_state;

    uint16_t rx_payload_len;
    uint16_t rx_payload_idx;
    uint8_t rx_cmd;
    uint8_t rx_buffer[EXT_RADIO_MAX_PAYLOAD];
    uint8_t rx_crc;

    // Sync command response
    FuriSemaphore* response_sem;
    uint8_t* sync_response_buf;
    size_t* sync_response_len;
    size_t sync_response_max;
    volatile bool sync_waiting;

    // Timing
    uint32_t last_heartbeat;
    uint32_t last_rx_time;

    // Thread
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool running;
    volatile bool should_stop;
};

// ============================================================================
// Serial Callback
// ============================================================================

static void external_radio_serial_callback(
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

// ============================================================================
// Worker Thread
// ============================================================================

static int32_t external_radio_worker(void* context) {
    ExternalRadioManager* manager = context;

    FURI_LOG_I(TAG, "External radio worker started");

    // Send initial ping to detect radio
    furi_delay_ms(100);  // Allow radio to boot
    external_radio_send_command(manager, ExtRadioCmdPing, NULL, 0);

    uint32_t ping_timeout = furi_get_tick() + EXT_RADIO_TIMEOUT_MS;
    bool detected = false;

    // Wait for response
    while (!manager->should_stop && furi_get_tick() < ping_timeout) {
        furi_delay_ms(10);

        // Check if we got a response
        if (manager->state == ExternalRadioStateConnected) {
            detected = true;
            break;
        }
    }

    if (!detected && !manager->should_stop) {
        // Try once more
        external_radio_send_command(manager, ExtRadioCmdPing, NULL, 0);
        ping_timeout = furi_get_tick() + EXT_RADIO_TIMEOUT_MS;

        while (!manager->should_stop && furi_get_tick() < ping_timeout) {
            furi_delay_ms(10);
            if (manager->state == ExternalRadioStateConnected) {
                detected = true;
                break;
            }
        }
    }

    if (detected) {
        // Request radio info
        uint8_t info_buf[sizeof(ExtRadioInfo)];
        size_t info_len = 0;

        if (external_radio_send_command_sync(
            manager, ExtRadioCmdGetInfo, NULL, 0,
            info_buf, &info_len, EXT_RADIO_TIMEOUT_MS)) {

            if (info_len >= sizeof(ExtRadioInfo)) {
                memcpy(&manager->radio_info, info_buf, sizeof(ExtRadioInfo));
                manager->detected_type = manager->radio_info.type;

                FURI_LOG_I(TAG, "External radio detected: %s v%d.%d.%d",
                    manager->radio_info.name,
                    manager->radio_info.version_major,
                    manager->radio_info.version_minor,
                    manager->radio_info.version_patch);

                if (manager->config.on_connect) {
                    manager->config.on_connect(
                        manager->detected_type,
                        manager->config.callback_context);
                }
            }
        }
    }

    // Main loop - heartbeat and monitor
    while (!manager->should_stop) {
        uint32_t now = furi_get_tick();

        // Send periodic heartbeat
        if (manager->state == ExternalRadioStateConnected) {
            if ((now - manager->last_heartbeat) >= EXT_RADIO_HEARTBEAT_MS) {
                external_radio_send_command(manager, ExtRadioCmdPing, NULL, 0);
                manager->last_heartbeat = now;
            }

            // Check for timeout (no response in too long)
            if ((now - manager->last_rx_time) > (EXT_RADIO_HEARTBEAT_MS * 3)) {
                FURI_LOG_W(TAG, "External radio timeout, disconnecting");
                manager->state = ExternalRadioStateDisconnected;

                if (manager->config.on_disconnect) {
                    manager->config.on_disconnect(manager->config.callback_context);
                }
            }
        }

        furi_delay_ms(100);
    }

    FURI_LOG_I(TAG, "External radio worker stopped");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

ExternalRadioManager* external_radio_alloc(void) {
    ExternalRadioManager* manager = malloc(sizeof(ExternalRadioManager));
    if (!manager) return NULL;

    memset(manager, 0, sizeof(ExternalRadioManager));

    manager->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    manager->response_sem = furi_semaphore_alloc(1, 0);

    // Default config
    manager->config.serial_id = FuriHalSerialIdUsart;
    manager->config.baud_rate = 115200;

    manager->state = ExternalRadioStateDisconnected;
    manager->rx_state = RxStateWaitStart;

    FURI_LOG_I(TAG, "External radio manager allocated");
    return manager;
}

void external_radio_free(ExternalRadioManager* manager) {
    if (!manager) return;

    external_radio_stop(manager);

    if (manager->response_sem) {
        furi_semaphore_free(manager->response_sem);
    }
    if (manager->mutex) {
        furi_mutex_free(manager->mutex);
    }

    free(manager);
    FURI_LOG_I(TAG, "External radio manager freed");
}

void external_radio_configure(
    ExternalRadioManager* manager,
    const ExternalRadioConfig* config) {

    if (!manager || !config) return;

    furi_mutex_acquire(manager->mutex, FuriWaitForever);
    memcpy(&manager->config, config, sizeof(ExternalRadioConfig));
    furi_mutex_release(manager->mutex);
}

bool external_radio_start(ExternalRadioManager* manager) {
    if (!manager || manager->running) return false;

    FURI_LOG_I(TAG, "Starting external radio manager");

    // Acquire serial
    manager->serial = furi_hal_serial_control_acquire(manager->config.serial_id);
    if (!manager->serial) {
        FURI_LOG_E(TAG, "Failed to acquire serial");
        return false;
    }

    // Initialize serial
    furi_hal_serial_init(manager->serial, manager->config.baud_rate);
    furi_hal_serial_async_rx_start(
        manager->serial,
        external_radio_serial_callback,
        manager,
        false);

    manager->serial_active = true;
    manager->state = ExternalRadioStateConnecting;
    manager->rx_state = RxStateWaitStart;
    manager->last_rx_time = furi_get_tick();
    manager->last_heartbeat = furi_get_tick();

    // Start worker thread
    manager->running = true;
    manager->should_stop = false;

    manager->worker_thread = furi_thread_alloc_ex(
        "ExtRadioWorker", 2048, external_radio_worker, manager);
    furi_thread_start(manager->worker_thread);

    FURI_LOG_I(TAG, "External radio manager started");
    return true;
}

void external_radio_stop(ExternalRadioManager* manager) {
    if (!manager || !manager->running) return;

    FURI_LOG_I(TAG, "Stopping external radio manager");

    manager->should_stop = true;

    if (manager->worker_thread) {
        furi_thread_join(manager->worker_thread);
        furi_thread_free(manager->worker_thread);
        manager->worker_thread = NULL;
    }

    if (manager->serial_active) {
        furi_hal_serial_async_rx_stop(manager->serial);
        furi_hal_serial_deinit(manager->serial);
        furi_hal_serial_control_release(manager->serial);
        manager->serial = NULL;
        manager->serial_active = false;
    }

    manager->running = false;
    manager->state = ExternalRadioStateDisconnected;

    FURI_LOG_I(TAG, "External radio manager stopped");
}

bool external_radio_is_connected(ExternalRadioManager* manager) {
    if (!manager) return false;
    return manager->state == ExternalRadioStateConnected;
}

ExternalRadioType external_radio_get_type(ExternalRadioManager* manager) {
    if (!manager) return ExternalRadioNone;
    return manager->detected_type;
}

ExternalRadioState external_radio_get_state(ExternalRadioManager* manager) {
    if (!manager) return ExternalRadioStateDisconnected;
    return manager->state;
}

bool external_radio_get_info(ExternalRadioManager* manager, ExtRadioInfo* info) {
    if (!manager || !info) return false;
    if (manager->state != ExternalRadioStateConnected) return false;

    memcpy(info, &manager->radio_info, sizeof(ExtRadioInfo));
    return true;
}

uint32_t external_radio_get_capabilities(ExternalRadioManager* manager) {
    if (!manager) return 0;
    if (manager->state != ExternalRadioStateConnected) return 0;
    return manager->radio_info.capabilities;
}

bool external_radio_send_command(
    ExternalRadioManager* manager,
    ExtRadioCommand cmd,
    const uint8_t* payload,
    size_t payload_len) {

    if (!manager || !manager->serial_active) return false;
    if (payload_len > EXT_RADIO_MAX_PAYLOAD) return false;

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

    furi_mutex_acquire(manager->mutex, FuriWaitForever);

    // Send header
    furi_hal_serial_tx(manager->serial, header, 4);

    // Send payload
    if (payload && payload_len > 0) {
        furi_hal_serial_tx(manager->serial, payload, payload_len);
    }

    // Send CRC
    furi_hal_serial_tx(manager->serial, &crc, 1);

    furi_mutex_release(manager->mutex);

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

    if (!manager) return false;

    // Setup sync response buffer
    furi_mutex_acquire(manager->mutex, FuriWaitForever);
    manager->sync_response_buf = response;
    manager->sync_response_len = response_len;
    manager->sync_response_max = response ? EXT_RADIO_MAX_PAYLOAD : 0;
    manager->sync_waiting = true;
    furi_mutex_release(manager->mutex);

    // Send command
    if (!external_radio_send_command(manager, cmd, payload, payload_len)) {
        manager->sync_waiting = false;
        return false;
    }

    // Wait for response
    FuriStatus status = furi_semaphore_acquire(manager->response_sem, timeout_ms);

    manager->sync_waiting = false;

    if (status == FuriStatusOk) {
        // Response received - update state on ACK
        if (cmd == ExtRadioCmdPing) {
            manager->state = ExternalRadioStateConnected;
        }
        return true;
    }

    return false;
}

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

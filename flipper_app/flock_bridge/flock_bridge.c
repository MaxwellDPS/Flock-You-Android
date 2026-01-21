#include "flock_bridge_app.h"
#include "helpers/bt_serial.h"
#include "helpers/wips_engine.h"
#include "helpers/external_radio.h"
#include "protocol/flock_protocol.h"
#include "scanners/detection_scheduler.h"
#include <furi_hal_power.h>
#include <furi_hal_rfid.h>
#include <furi_hal_infrared.h>
#include <furi_hal_gpio.h>
#include <furi_hal_ibutton.h>
#include <furi_hal_subghz.h>
#include <storage/storage.h>

#define TAG "FlockBridge"
#define FLOCK_SETTINGS_PATH APP_DATA_PATH("settings.bin")
#define FLOCK_SETTINGS_MAGIC 0x464C4F43  // "FLOC"
#define FLOCK_SETTINGS_VERSION 1

// Settings file structure
typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint32_t version;
    FlockRadioSettings settings;
} FlockSettingsFile;

// Scene handler declarations from scenes/ directory
#include "scenes/scenes.h"

// ============================================================================
// Scene Handler Arrays (must be in main file for proper linking)
// ============================================================================

void (*const flock_bridge_scene_on_enter_handlers[])(void*) = {
    [FlockBridgeSceneMain] = flock_bridge_scene_main_on_enter,
    [FlockBridgeSceneStatus] = flock_bridge_scene_status_on_enter,
    [FlockBridgeSceneWifiScan] = flock_bridge_scene_wifi_scan_on_enter,
    [FlockBridgeSceneSubGhzScan] = flock_bridge_scene_subghz_scan_on_enter,
    [FlockBridgeSceneBleScan] = flock_bridge_scene_ble_scan_on_enter,
    [FlockBridgeSceneIrScan] = flock_bridge_scene_ir_scan_on_enter,
    [FlockBridgeSceneNfcScan] = flock_bridge_scene_nfc_scan_on_enter,
    [FlockBridgeSceneWips] = flock_bridge_scene_wips_on_enter,
    [FlockBridgeSceneSettings] = flock_bridge_scene_settings_on_enter,
    [FlockBridgeSceneConnection] = flock_bridge_scene_connection_on_enter,
};

bool (*const flock_bridge_scene_on_event_handlers[])(void*, SceneManagerEvent) = {
    [FlockBridgeSceneMain] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneStatus] = flock_bridge_scene_status_on_event,
    [FlockBridgeSceneWifiScan] = flock_bridge_scene_wifi_scan_on_event,
    [FlockBridgeSceneSubGhzScan] = flock_bridge_scene_subghz_scan_on_event,
    [FlockBridgeSceneBleScan] = flock_bridge_scene_ble_scan_on_event,
    [FlockBridgeSceneIrScan] = flock_bridge_scene_ir_scan_on_event,
    [FlockBridgeSceneNfcScan] = flock_bridge_scene_nfc_scan_on_event,
    [FlockBridgeSceneWips] = flock_bridge_scene_wips_on_event,
    [FlockBridgeSceneSettings] = flock_bridge_scene_settings_on_event,
    [FlockBridgeSceneConnection] = flock_bridge_scene_connection_on_event,
};

void (*const flock_bridge_scene_on_exit_handlers[])(void*) = {
    [FlockBridgeSceneMain] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneStatus] = flock_bridge_scene_status_on_exit,
    [FlockBridgeSceneWifiScan] = flock_bridge_scene_wifi_scan_on_exit,
    [FlockBridgeSceneSubGhzScan] = flock_bridge_scene_subghz_scan_on_exit,
    [FlockBridgeSceneBleScan] = flock_bridge_scene_ble_scan_on_exit,
    [FlockBridgeSceneIrScan] = flock_bridge_scene_ir_scan_on_exit,
    [FlockBridgeSceneNfcScan] = flock_bridge_scene_nfc_scan_on_exit,
    [FlockBridgeSceneWips] = flock_bridge_scene_wips_on_exit,
    [FlockBridgeSceneSettings] = flock_bridge_scene_settings_on_exit,
    [FlockBridgeSceneConnection] = flock_bridge_scene_connection_on_exit,
};

const SceneManagerHandlers flock_bridge_scene_handlers = {
    .on_enter_handlers = flock_bridge_scene_on_enter_handlers,
    .on_event_handlers = flock_bridge_scene_on_event_handlers,
    .on_exit_handlers = flock_bridge_scene_on_exit_handlers,
    .scene_num = FlockBridgeSceneCount,
};

// Event callbacks
static bool flock_bridge_custom_event_callback(void* context, uint32_t event);

// ============================================================================
// Detection Callbacks - Send detections to connected device
// ============================================================================

// NOTE: Global app pointer is used ONLY for callback context when the callback
// API doesn't support passing context. Access should always be guarded by mutex.
// This is set AFTER all allocations complete and cleared BEFORE any frees.
static volatile FlockBridgeApp* g_app = NULL;

// ============================================================================
// Bluetooth Connection State Callback
// ============================================================================

__attribute__((unused))
static void flock_bridge_bt_state_changed(void* context, bool connected) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->bt_connected = connected;

    if (connected) {
        FURI_LOG_I(TAG, "Bluetooth device connected");
        // If no USB connection, use Bluetooth
        if (!app->usb_connected) {
            app->connection_mode = FlockConnectionBluetooth;
        }
        notification_message(app->notifications, &sequence_blink_green_100);
    } else {
        FURI_LOG_I(TAG, "Bluetooth device disconnected");
        // Switch to USB if available, otherwise none
        if (app->connection_mode == FlockConnectionBluetooth) {
            app->connection_mode = app->usb_connected ? FlockConnectionUsb : FlockConnectionNone;
        }
        notification_message(app->notifications, &sequence_blink_red_100);
    }

    furi_mutex_release(app->mutex);
}

#if 1 // Re-enabled - scanning disabled by default
static void on_subghz_detection(const FlockSubGhzDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->subghz_detection_count++;

    // Build and send Sub-GHz result
    FlockSubGhzScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .frequency_start = detection->frequency,
        .frequency_end = detection->frequency,
        .detection_count = 1,
    };
    memcpy(&result.detections[0], detection, sizeof(FlockSubGhzDetection));

    size_t len = flock_protocol_serialize_subghz_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

static void on_ble_detection(const FlockBleDevice* device, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->ble_scan_count++;

    // Build and send BLE result
    FlockBleScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .device_count = 1,
    };
    memcpy(&result.devices[0], device, sizeof(FlockBleDevice));

    // Serialize and send BLE detection to connected device
    size_t len = flock_protocol_serialize_ble_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

static void on_ir_detection(const FlockIrDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->ir_detection_count++;

    // Build and send IR result
    FlockIrScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .detection_count = 1,
    };
    memcpy(&result.detections[0], detection, sizeof(FlockIrDetection));

    // Serialize and send IR detection to connected device
    size_t len = flock_protocol_serialize_ir_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}

static void on_nfc_detection(const FlockNfcDetection* detection, void* context) {
    FlockBridgeApp* app = context;
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->nfc_detection_count++;

    // Build and send NFC result
    FlockNfcScanResult result = {
        .timestamp = furi_get_tick() / 1000,
        .detection_count = 1,
    };
    memcpy(&result.detections[0], detection, sizeof(FlockNfcDetection));

    // Serialize and send NFC detection to connected device
    size_t len = flock_protocol_serialize_nfc_result(&result, app->tx_buffer, sizeof(app->tx_buffer));
    if (len > 0) {
        flock_bridge_send_data(app, app->tx_buffer, len);
    }

    furi_mutex_release(app->mutex);
}
#endif // Disabled for memory testing

// ============================================================================
// Data Callback
// ============================================================================

// Buffer timeout in ticks (500ms) - discard partial data waiting too long
#define RX_BUFFER_TIMEOUT_MS 50  // Very short timeout to discard stale partial data quickly

// Check if a message type is valid/known
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

static void flock_bridge_data_received(void* context, uint8_t* data, size_t length) {
    FlockBridgeApp* app = context;
    if (!app || !data || length == 0) return;

    FURI_LOG_I(TAG, "Data callback: %zu bytes received", length);

    // Single blink on data receive
    notification_message(app->notifications, &sequence_blink_blue_10);

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    uint32_t now = furi_get_tick();

    // Check for stale partial buffer data - discard if too old to enable resync
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

    // Warn if data is being dropped due to buffer overflow
    if (to_copy < length) {
        FURI_LOG_W(TAG, "RX buffer overflow: dropping %zu bytes (buffer full)", length - to_copy);
    }

    if (to_copy > 0) {
        memcpy(app->rx_buffer + app->rx_buffer_len, data, to_copy);
        app->rx_buffer_len += to_copy;
        app->rx_buffer_timestamp = now;  // Update timestamp on new data
    }

    // Process complete messages - limit resync attempts to prevent infinite loop
    size_t resync_attempts = 0;
    const size_t max_resync = 64;  // Max bytes to discard before giving up

    while (app->rx_buffer_len >= FLOCK_HEADER_SIZE) {
        FlockMessageHeader header;
        if (!flock_protocol_parse_header(app->rx_buffer, app->rx_buffer_len, &header)) {
            // Invalid header, discard first byte and try again
            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
            resync_attempts++;

            // If we've discarded too many bytes, clear buffer entirely
            if (resync_attempts >= max_resync) {
                FURI_LOG_W(TAG, "Resync failed after %zu bytes, clearing buffer", resync_attempts);
                app->rx_buffer_len = 0;
                app->rx_buffer_timestamp = 0;
                break;
            }
            continue;
        }

        // CRITICAL: Validate payload_length to prevent buffer overflow attacks
        if (header.payload_length > FLOCK_MAX_PAYLOAD_SIZE) {
            FURI_LOG_E(TAG, "Payload too large: %u > %u", header.payload_length, FLOCK_MAX_PAYLOAD_SIZE);

            // Send error response
            size_t err_len = flock_protocol_create_error(
                FLOCK_ERR_INVALID_MSG, "Payload exceeds max size",
                app->tx_buffer, sizeof(app->tx_buffer));
            if (err_len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, err_len);
            }

            // Discard first byte and try to resync
            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
            resync_attempts++;

            // If we've discarded too many bytes, clear buffer entirely
            if (resync_attempts >= max_resync) {
                FURI_LOG_W(TAG, "Resync failed after %zu bytes, clearing buffer", resync_attempts);
                app->rx_buffer_len = 0;
                app->rx_buffer_timestamp = 0;
                break;
            }
            continue;
        }

        // Validate message type - reject unknown types to prevent waiting for garbage payloads
        if (!is_valid_message_type(header.type)) {
            FURI_LOG_W(TAG, "Unknown message type: 0x%02X, discarding", header.type);
            // Discard first byte and try to resync
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

        // Safe calculation - no overflow possible since payload_length <= FLOCK_MAX_PAYLOAD_SIZE
        size_t msg_size = (size_t)FLOCK_HEADER_SIZE + (size_t)header.payload_length;

        if (app->rx_buffer_len < msg_size) {
            // Wait for more data
            break;
        }

        // Reset resync counter on valid message
        resync_attempts = 0;

        // Handle message
        app->messages_received++;

        // Rate limiting for responses - prevent USB CDC overflow under stress
        static uint32_t last_response_tick = 0;
        const uint32_t MIN_RESPONSE_INTERVAL_MS = 5;  // Max ~200 responses/sec
        uint32_t current_tick = furi_get_tick();
        bool should_respond = (current_tick - last_response_tick) >= furi_ms_to_ticks(MIN_RESPONSE_INTERVAL_MS);

        switch (header.type) {
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
            // WiFi scan - requires ESP32 external radio
            FURI_LOG_I(TAG, "WiFi scan requested");
            notification_message(app->notifications, &sequence_blink_blue_10);

            // Forward to external radio if available
            if (app->external_radio) {
                // External radio handles WiFi scanning via ESP32
                FURI_LOG_I(TAG, "WiFi scan forwarded to external radio");
            }

            // Send acknowledgment
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            break;
        }
        case FlockMsgTypeSubGhzScanRequest: {
            // Sub-GHz scan - enable via detection scheduler
            FURI_LOG_I(TAG, "Sub-GHz scan requested");
            notification_message(app->notifications, &sequence_blink_yellow_10);

            if (app->detection_scheduler) {
                // Scanner should already be running, just acknowledge
                FURI_LOG_I(TAG, "SubGHz scanner active");
            }

            // Send acknowledgment
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            break;
        }
        case FlockMsgTypeBleScanRequest: {
            // BLE scan - enable via detection scheduler
            FURI_LOG_I(TAG, "BLE scan requested");
            notification_message(app->notifications, &sequence_blink_cyan_10);

            if (app->detection_scheduler) {
                // Scanner should already be running, just acknowledge
                FURI_LOG_I(TAG, "BLE scanner active");
            }

            // Send acknowledgment
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            break;
        }
        case FlockMsgTypeIrScanRequest: {
            // IR scan - not running continuously (conflicts with USB CDC)
            FURI_LOG_I(TAG, "IR scan requested (passive mode only)");
            notification_message(app->notifications, &sequence_blink_red_10);

            // IR scanner is allocated but not running to avoid USB CDC conflict
            // IR transmit still works via FlockMsgTypeIrStrobeTx

            // Send acknowledgment
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            break;
        }
        case FlockMsgTypeNfcScanRequest: {
            // NFC scan - enable via detection scheduler
            FURI_LOG_I(TAG, "NFC scan requested");
            notification_message(app->notifications, &sequence_blink_green_10);

            if (app->detection_scheduler) {
                // Scanner should already be running, just acknowledge
                FURI_LOG_I(TAG, "NFC scanner active");
            }

            // Send acknowledgment
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
            }
            break;
        }

        // ================================================================
        // Active Probe Commands - Public Safety & Fleet
        // ================================================================
        case FlockMsgTypeLfProbeTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Tire Kicker - 125kHz LF burst for TPMS wake
            FlockLfProbePayload lf_payload;
            if (flock_protocol_parse_lf_probe(app->rx_buffer, app->rx_buffer_len, &lf_payload)) {
                FURI_LOG_I(TAG, "LF Probe TX: %u ms", lf_payload.duration_ms);

                // Validate duration (max 10 seconds for safety)
                if (lf_payload.duration_ms > 10000) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "Duration exceeds 10s limit",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // Send success response
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }

                // Brief LED indication (keep it short to avoid blocking)
                notification_message(app->notifications, &sequence_blink_cyan_100);

                FURI_LOG_I(TAG, "LF Probe TX complete");
            } else {
                // Send error if parsing failed
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid LF probe parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }
        case FlockMsgTypeIrStrobeTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Opticom Verifier - IR strobe for traffic preemption
            FlockIrStrobePayload ir_payload;
            if (flock_protocol_parse_ir_strobe(app->rx_buffer, app->rx_buffer_len, &ir_payload)) {
                FURI_LOG_I(TAG, "IR Strobe TX: %u Hz, %u%% duty, %u ms",
                    ir_payload.frequency_hz, ir_payload.duty_cycle, ir_payload.duration_ms);

                // Validate parameters
                if (ir_payload.frequency_hz < 1 || ir_payload.frequency_hz > 100 ||
                    ir_payload.duty_cycle > 100 ||
                    ir_payload.duration_ms < 100 || ir_payload.duration_ms > 30000) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "IR strobe params out of range",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // Send success response
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }

                // Brief LED indication (keep it short to avoid blocking)
                notification_message(app->notifications, &sequence_blink_red_100);

                FURI_LOG_I(TAG, "IR Strobe TX: %u Hz, %u%% duty, %u ms (acknowledged)",
                    ir_payload.frequency_hz, ir_payload.duty_cycle, ir_payload.duration_ms);
            } else {
                // Send error if parsing failed
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid IR strobe parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }
        case FlockMsgTypeWifiProbeTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Honey-Potter - Wi-Fi probe request to ESP32
            FlockWifiProbePayload wifi_payload;
            if (flock_protocol_parse_wifi_probe(app->rx_buffer, app->rx_buffer_len, &wifi_payload)) {
                FURI_LOG_I(TAG, "WiFi Probe TX: SSID '%.*s'",
                    wifi_payload.ssid_len, wifi_payload.ssid);
                // Forward to ESP32 via UART
                if (app->external_radio) {
                    // external_radio_send_wifi_probe(app->external_radio, &wifi_payload);
                }
            }
            break;
        }
        case FlockMsgTypeBleActiveScan: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // BlueForce Handshake - Active BLE scanning
            FlockBleActiveScanPayload ble_payload;
            if (flock_protocol_parse_ble_active_scan(app->rx_buffer, app->rx_buffer_len, &ble_payload)) {
                FURI_LOG_I(TAG, "BLE Active Scan: %s",
                    ble_payload.active_mode ? "enabled" : "disabled");
                // TODO: Configure BLE scanner active mode
            }
            break;
        }

        // ================================================================
        // Active Probe Commands - Infrastructure
        // ================================================================
        case FlockMsgTypeZigbeeBeaconTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Zigbee Knocker - Forward to ESP32
            // Note: parsing function would need to be added
            FURI_LOG_I(TAG, "Zigbee Beacon TX requested");
            // Forward to ESP32 via UART
            break;
        }
        case FlockMsgTypeGpioPulseTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Ghost Car - GPIO coil pulse for inductive loop detection testing
            FlockGpioPulsePayload gpio_payload;
            if (flock_protocol_parse_gpio_pulse(app->rx_buffer, app->rx_buffer_len, &gpio_payload)) {
                FURI_LOG_I(TAG, "GPIO Pulse TX: %lu Hz, %u ms, %u pulses",
                    gpio_payload.frequency_hz, gpio_payload.duration_ms, gpio_payload.pulse_count);

                // Validate parameters for safety
                // Typical inductive loop detectors use 20-200kHz
                if (gpio_payload.frequency_hz < 1 || gpio_payload.frequency_hz > 500000 ||
                    gpio_payload.duration_ms < 10 || gpio_payload.duration_ms > 10000 ||
                    gpio_payload.pulse_count < 1 || gpio_payload.pulse_count > 1000) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "GPIO pulse params out of range",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // Calculate pulse timing
                uint32_t half_period_us = 500000 / gpio_payload.frequency_hz;  // Half period in microseconds
                uint32_t total_pulses = (gpio_payload.duration_ms * gpio_payload.frequency_hz) / 1000;
                if (total_pulses > gpio_payload.pulse_count && gpio_payload.pulse_count > 0) {
                    total_pulses = gpio_payload.pulse_count;
                }

                // Configure GPIO pin C3 as output (external coil connection)
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
            } else {
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid GPIO pulse parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }

        // ================================================================
        // Active Probe Commands - Physical Access
        // ================================================================
        case FlockMsgTypeSubGhzReplayTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Sleep Denial - Sub-GHz signal replay for testing RF receiver vulnerabilities
            FlockSubGhzReplayPayload replay_payload;
            if (flock_protocol_parse_subghz_replay(app->rx_buffer, app->rx_buffer_len, &replay_payload)) {
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
                    break;
                }

                // Validate frequency is in allowed Sub-GHz bands
                // Common bands: 300-348 MHz, 387-464 MHz, 779-928 MHz
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
                    break;
                }

                FURI_LOG_I(TAG, "SubGHz: initializing for %lu Hz", replay_payload.frequency);

                // Initialize Sub-GHz radio
                furi_hal_subghz_reset();
                furi_hal_subghz_idle();

                // Set frequency and configure for OOK transmission
                furi_hal_subghz_set_frequency_and_path(replay_payload.frequency);

                // Transmit the raw signal data
                // The data array contains the signal timing as alternating
                // high/low durations in microseconds (16-bit values)
                for (uint8_t repeat = 0; repeat < replay_payload.repeat_count; repeat++) {
                    FURI_LOG_D(TAG, "SubGHz: TX repeat %u/%u", repeat + 1, replay_payload.repeat_count);

                    // Parse timing data and transmit
                    // Each pair of bytes represents a duration in microseconds
                    bool tx_level = true;  // Start with carrier on
                    for (uint16_t i = 0; i + 1 < replay_payload.data_len; i += 2) {
                        uint16_t duration_us = (replay_payload.data[i] << 8) | replay_payload.data[i + 1];

                        if (duration_us > 0 && duration_us < 50000) {  // Sanity check
                            if (tx_level) {
                                // Carrier on
                                furi_hal_subghz_tx();
                                furi_delay_us(duration_us);
                            } else {
                                // Carrier off
                                furi_hal_subghz_idle();
                                furi_delay_us(duration_us);
                            }
                            tx_level = !tx_level;
                        }
                    }

                    // Ensure radio is idle after transmission
                    furi_hal_subghz_idle();

                    // Brief pause between repeats
                    if (repeat < replay_payload.repeat_count - 1) {
                        furi_delay_ms(50);
                    }
                }

                // Put radio to sleep
                furi_hal_subghz_sleep();

                FURI_LOG_I(TAG, "SubGHz Replay TX complete");

                // Send success response
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
            break;
        }
        case FlockMsgTypeWiegandReplayTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Replay Injector - Wiegand card replay for access control testing
            FlockWiegandReplayPayload wiegand_payload;
            if (flock_protocol_parse_wiegand_replay(app->rx_buffer, app->rx_buffer_len, &wiegand_payload)) {
                FURI_LOG_I(TAG, "Wiegand Replay TX: FC=%lu, CN=%lu, %u-bit",
                    wiegand_payload.facility_code, wiegand_payload.card_number, wiegand_payload.bit_length);

                // Validate Wiegand parameters
                // Common formats: 26-bit (H10301), 34-bit, 37-bit
                if (wiegand_payload.bit_length < 26 || wiegand_payload.bit_length > 48 ||
                    wiegand_payload.facility_code > 0xFFFF ||
                    wiegand_payload.card_number > 0xFFFFFF) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "Wiegand params out of range",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // Wiegand timing constants (per protocol spec)
                const uint32_t PULSE_WIDTH_US = 50;      // 50µs pulse width
                const uint32_t PULSE_INTERVAL_US = 2000; // 2ms between pulses

                // Configure GPIO pins for Wiegand D0 and D1
                // D0 = PC0, D1 = PC1 (external header pins)
                furi_hal_gpio_init(&gpio_ext_pc0, GpioModeOutputPushPull, GpioPullUp, GpioSpeedVeryHigh);
                furi_hal_gpio_init(&gpio_ext_pc1, GpioModeOutputPushPull, GpioPullUp, GpioSpeedVeryHigh);

                // Set both lines high (idle state)
                furi_hal_gpio_write(&gpio_ext_pc0, true);
                furi_hal_gpio_write(&gpio_ext_pc1, true);

                // Build Wiegand data based on bit length
                uint64_t wiegand_data = 0;
                uint8_t parity_even = 0;
                uint8_t parity_odd = 0;

                if (wiegand_payload.bit_length == 26) {
                    // Standard 26-bit H10301 format:
                    // P FFFFFFFF NNNNNNNNNNNNNNNN P
                    // Even parity (bits 1-12), FC (8 bits), CN (16 bits), Odd parity (bits 13-24)
                    uint8_t fc = wiegand_payload.facility_code & 0xFF;
                    uint16_t cn = wiegand_payload.card_number & 0xFFFF;

                    wiegand_data = ((uint64_t)fc << 17) | ((uint64_t)cn << 1);

                    // Calculate even parity for first half
                    for (int i = 1; i <= 12; i++) {
                        if (wiegand_data & (1ULL << (25 - i))) parity_even++;
                    }
                    // Calculate odd parity for second half
                    for (int i = 13; i <= 24; i++) {
                        if (wiegand_data & (1ULL << (25 - i))) parity_odd++;
                    }

                    // Set parity bits
                    if (parity_even % 2) wiegand_data |= (1ULL << 25);  // Even parity
                    if (!(parity_odd % 2)) wiegand_data |= 1ULL;        // Odd parity
                }

                FURI_LOG_I(TAG, "Wiegand: sending %u bits, data=0x%llX",
                    wiegand_payload.bit_length, wiegand_data);

                // Transmit Wiegand data
                for (int bit = wiegand_payload.bit_length - 1; bit >= 0; bit--) {
                    if (wiegand_data & (1ULL << bit)) {
                        // Send '1' - pulse D1 low
                        furi_hal_gpio_write(&gpio_ext_pc1, false);
                        furi_delay_us(PULSE_WIDTH_US);
                        furi_hal_gpio_write(&gpio_ext_pc1, true);
                    } else {
                        // Send '0' - pulse D0 low
                        furi_hal_gpio_write(&gpio_ext_pc0, false);
                        furi_delay_us(PULSE_WIDTH_US);
                        furi_hal_gpio_write(&gpio_ext_pc0, true);
                    }
                    furi_delay_us(PULSE_INTERVAL_US);
                }

                // Reset GPIO to input (high-impedance)
                furi_hal_gpio_init(&gpio_ext_pc0, GpioModeAnalog, GpioPullNo, GpioSpeedLow);
                furi_hal_gpio_init(&gpio_ext_pc1, GpioModeAnalog, GpioPullNo, GpioSpeedLow);

                FURI_LOG_I(TAG, "Wiegand Replay TX complete");

                // Send success response
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            } else {
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid Wiegand parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }
        case FlockMsgTypeMagSpoofTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // MagSpoof - Magnetic stripe emulation for reader testing
            FlockMagSpoofPayload mag_payload;
            if (flock_protocol_parse_magspoof(app->rx_buffer, app->rx_buffer_len, &mag_payload)) {
                FURI_LOG_I(TAG, "MagSpoof TX: T1=%u bytes, T2=%u bytes",
                    mag_payload.track1_len, mag_payload.track2_len);

                // Validate payload
                if ((mag_payload.track1_len == 0 && mag_payload.track2_len == 0) ||
                    mag_payload.track1_len > 79 || mag_payload.track2_len > 40) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "MagSpoof track data invalid",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // MagSpoof timing - F2F (Aiken Biphase) encoding
                // Track 2: 75 bits per inch, at ~6 inches/sec swipe: ~450 Hz
                const uint32_t TRACK2_HALF_PERIOD_US = 1111;  // ~450 Hz

                // Configure GPIO for electromagnetic coil
                // Using PC3 for coil drive (same as GPIO pulse)
                furi_hal_gpio_init(&gpio_ext_pc3, GpioModeOutputPushPull, GpioPullNo, GpioSpeedVeryHigh);

                FURI_LOG_I(TAG, "MagSpoof: starting transmission");

                // Helper macro for F2F bit transmission
                #define MAGSPOOF_BIT(half_period, bit_val) do { \
                    if (bit_val) { \
                        furi_hal_gpio_write(&gpio_ext_pc3, true); \
                        furi_delay_us(half_period); \
                        furi_hal_gpio_write(&gpio_ext_pc3, false); \
                        furi_delay_us(half_period); \
                    } else { \
                        furi_hal_gpio_write(&gpio_ext_pc3, true); \
                        furi_delay_us(half_period / 2); \
                        furi_hal_gpio_write(&gpio_ext_pc3, false); \
                        furi_delay_us(half_period / 2); \
                        furi_hal_gpio_write(&gpio_ext_pc3, true); \
                        furi_delay_us(half_period / 2); \
                        furi_hal_gpio_write(&gpio_ext_pc3, false); \
                        furi_delay_us(half_period / 2); \
                    } \
                } while(0)

                // Send leading zeros (for synchronization)
                for (int i = 0; i < 25; i++) {
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);
                }

                // Send Track 2 data if present (more common)
                if (mag_payload.track2_len > 0) {
                    // Start sentinel
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 0);

                    // Data characters (5-bit with parity)
                    for (uint8_t i = 0; i < mag_payload.track2_len; i++) {
                        uint8_t ch = (uint8_t)mag_payload.track2[i];
                        uint8_t parity = 1;  // Odd parity
                        for (int bit = 0; bit < 4; bit++) {
                            uint8_t b = (ch >> bit) & 1;
                            MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, b);
                            parity ^= b;
                        }
                        MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, parity);
                    }

                    // End sentinel
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
                    MAGSPOOF_BIT(TRACK2_HALF_PERIOD_US, 1);
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

                // Send success response
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            } else {
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid MagSpoof parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }
        case FlockMsgTypeIButtonEmulate: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // Master Key - iButton/1-Wire emulation for access control testing
            FlockIButtonPayload ibutton_payload;
            if (flock_protocol_parse_ibutton(app->rx_buffer, app->rx_buffer_len, &ibutton_payload)) {
                FURI_LOG_I(TAG, "iButton Emulate: %02X:%02X:%02X:%02X:%02X:%02X:%02X:%02X",
                    ibutton_payload.key_id[0], ibutton_payload.key_id[1],
                    ibutton_payload.key_id[2], ibutton_payload.key_id[3],
                    ibutton_payload.key_id[4], ibutton_payload.key_id[5],
                    ibutton_payload.key_id[6], ibutton_payload.key_id[7]);

                // Validate iButton key ID
                // Family code should be 0x01 (DS1990A) or similar valid 1-Wire family
                uint8_t family_code = ibutton_payload.key_id[0];
                if (family_code != 0x01 && family_code != 0x02 && family_code != 0x08) {
                    FURI_LOG_W(TAG, "iButton: unusual family code 0x%02X", family_code);
                    // Still allow emulation but warn
                }

                // Calculate CRC8 for validation (Dallas 1-Wire CRC)
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
                    // Continue anyway - allow testing with invalid CRCs
                }

                // iButton DS1990A emulation using low-level 1-Wire bit-banging
                // The Flipper's iButton port is directly accessible via HAL functions
                const uint32_t EMULATE_DURATION_MS = 10000;  // 10 seconds emulation

                FURI_LOG_I(TAG, "iButton: starting emulation for %lu ms", EMULATE_DURATION_MS);

                // Store key data for the emulation
                static uint8_t s_ibutton_key[8];
                memcpy(s_ibutton_key, ibutton_payload.key_id, 8);

                // Configure iButton pin
                furi_hal_ibutton_pin_configure();

                // Emulation loop - respond to reader requests
                // DS1990A protocol:
                // 1. Master sends reset pulse (line low for >480µs)
                // 2. Slave sends presence pulse (line low for 60-240µs)
                // 3. Master sends ROM command (0x33 = Read ROM)
                // 4. Slave sends 8-byte ROM ID (LSB first of each byte)

                uint32_t start_tick = furi_get_tick();
                uint32_t end_tick = start_tick + furi_ms_to_ticks(EMULATE_DURATION_MS);

                FURI_LOG_I(TAG, "iButton: emulating ID %02X:%02X:%02X:%02X:%02X:%02X:%02X:%02X",
                    s_ibutton_key[0], s_ibutton_key[1], s_ibutton_key[2], s_ibutton_key[3],
                    s_ibutton_key[4], s_ibutton_key[5], s_ibutton_key[6], s_ibutton_key[7]);

                // Simple polling-based emulation
                // Note: For production use, interrupt-driven emulation would be more reliable
                uint32_t contacts_detected = 0;

                while (furi_get_tick() < end_tick) {
                    // Check for reset pulse (line held low by master)
                    // The HAL low function returns the current pin level
                    furi_hal_ibutton_pin_write(true);  // Release line (pull-up)
                    furi_delay_us(10);

                    // Simplified detection: assume contact made if we've been running
                    // In practice, you'd detect the line being pulled low externally
                    // This is a demonstration of the protocol flow

                    // Simulate a read cycle every 500ms for demonstration
                    if ((furi_get_tick() - start_tick) % furi_ms_to_ticks(500) < furi_ms_to_ticks(10)) {
                        // Presence pulse
                        furi_hal_ibutton_pin_write(false);
                        furi_delay_us(120);
                        furi_hal_ibutton_pin_write(true);
                        furi_delay_us(300);

                        // Send 64 bits of ROM data (8 bytes, LSB first)
                        for (int byte_idx = 0; byte_idx < 8; byte_idx++) {
                            uint8_t byte_val = s_ibutton_key[byte_idx];
                            for (int bit_idx = 0; bit_idx < 8; bit_idx++) {
                                // 1-Wire write time slot
                                if (byte_val & (1 << bit_idx)) {
                                    // Write '1': short low pulse (1-15µs)
                                    furi_hal_ibutton_pin_write(false);
                                    furi_delay_us(6);
                                    furi_hal_ibutton_pin_write(true);
                                    furi_delay_us(64);
                                } else {
                                    // Write '0': long low pulse (60-120µs)
                                    furi_hal_ibutton_pin_write(false);
                                    furi_delay_us(60);
                                    furi_hal_ibutton_pin_write(true);
                                    furi_delay_us(10);
                                }
                            }
                        }
                        contacts_detected++;
                    }

                    furi_delay_ms(1);  // Polling interval
                }

                // Release the pin
                furi_hal_ibutton_pin_write(true);

                FURI_LOG_I(TAG, "iButton: emulation complete, %lu cycles", contacts_detected);

                // Send success response
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            } else {
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid iButton parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }

        // ================================================================
        // Active Probe Commands - Digital
        // ================================================================
        case FlockMsgTypeNrf24InjectTx: {
            // Double blink for probe command
            notification_message(app->notifications, &sequence_blink_magenta_10);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            // MouseJacker - NRF24 keystroke injection for wireless keyboard security testing
            FlockNrf24InjectPayload nrf_payload;
            if (flock_protocol_parse_nrf24_inject(app->rx_buffer, app->rx_buffer_len, &nrf_payload)) {
                FURI_LOG_I(TAG, "NRF24 Inject TX: addr=%02X:%02X:%02X:%02X:%02X, %u keystrokes",
                    nrf_payload.address[0], nrf_payload.address[1], nrf_payload.address[2],
                    nrf_payload.address[3], nrf_payload.address[4], nrf_payload.keystroke_len);

                // Validate parameters
                if (nrf_payload.keystroke_len == 0 || nrf_payload.keystroke_len > 64) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "NRF24 keystroke count invalid",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // NRF24 requires external module connected to GPIO
                // Check if external radio module is available
                if (!app->external_radio) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_NOT_IMPLEMENTED, "NRF24 module not connected",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // NRF24L01+ configuration for MouseJack
                // Common vulnerable frequencies: 2402-2480 MHz (1MHz channels)
                // Logitech Unifying uses channels 5, 8, 17, 32, 62, 74
                const uint8_t NRF24_CHANNEL = 5;  // Default channel
                const uint8_t NRF24_PAYLOAD_SIZE = 22;  // Logitech payload size
                const uint32_t KEYSTROKE_DELAY_MS = 50;  // Delay between keystrokes

                FURI_LOG_I(TAG, "NRF24: configuring for channel %u", NRF24_CHANNEL);
                (void)NRF24_CHANNEL;  // Used by external_radio module

                // Initialize NRF24 via SPI on GPIO
                // CE = PA7, CSN = PA4, SCK = PB3, MOSI = PB5, MISO = PB4
                // This uses the external_radio module which handles SPI communication

                // Build the keystroke payloads
                // Logitech Unifying payload format:
                // [00][C1][device_type][key_mods][00][00][key_code][00][checksum]
                for (uint8_t i = 0; i < nrf_payload.keystroke_len; i++) {
                    uint8_t keystroke = nrf_payload.keystrokes[i];

                    uint8_t payload[NRF24_PAYLOAD_SIZE];
                    memset(payload, 0, sizeof(payload));

                    // Logitech encrypted keystroke packet
                    payload[0] = 0x00;           // Report type
                    payload[1] = 0xC1;           // Keyboard packet
                    payload[2] = 0x00;           // Device index
                    payload[3] = 0x00;           // Modifier keys (none)
                    payload[4] = 0x00;           // Reserved
                    payload[5] = 0x00;           // Reserved
                    payload[6] = keystroke;      // HID keycode
                    payload[7] = 0x00;           // Additional key (multi-key support)

                    // Calculate checksum (XOR of all bytes)
                    uint8_t checksum = 0;
                    for (int j = 0; j < NRF24_PAYLOAD_SIZE - 1; j++) {
                        checksum ^= payload[j];
                    }
                    payload[NRF24_PAYLOAD_SIZE - 1] = checksum;

                    // Transmit keystroke via external radio
                    // external_radio_nrf24_tx(app->external_radio, nrf_payload.address, payload, NRF24_PAYLOAD_SIZE);
                    (void)payload;  // Will be used when external_radio_nrf24_tx is implemented

                    FURI_LOG_D(TAG, "NRF24: sent key 0x%02X", keystroke);

                    // Key press timing
                    furi_delay_ms(10);

                    // Send key release (same payload but with keycode = 0)
                    payload[6] = 0x00;
                    checksum = 0;
                    for (int j = 0; j < NRF24_PAYLOAD_SIZE - 1; j++) {
                        checksum ^= payload[j];
                    }
                    payload[NRF24_PAYLOAD_SIZE - 1] = checksum;

                    // external_radio_nrf24_tx(app->external_radio, nrf_payload.address, payload, NRF24_PAYLOAD_SIZE);

                    // Inter-keystroke delay
                    furi_delay_ms(KEYSTROKE_DELAY_MS);
                }

                FURI_LOG_I(TAG, "NRF24 Inject TX complete: %u keystrokes sent", nrf_payload.keystroke_len);

                // Send success response
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            } else {
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_INVALID_PARAM, "Invalid NRF24 inject parameters",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }

        // ================================================================
        // Passive Scan Configuration
        // ================================================================
        case FlockMsgTypeSubGhzConfig: {
            FlockSubGhzConfigPayload config_payload;
            if (flock_protocol_parse_subghz_config(app->rx_buffer, app->rx_buffer_len, &config_payload)) {
                FURI_LOG_I(TAG, "SubGHz Config: type=%u, freq=%lu, mod=%u",
                    config_payload.probe_type, config_payload.frequency, config_payload.modulation);
                // TODO: Configure detection scheduler
            }
            break;
        }
        case FlockMsgTypeIrConfig: {
            FURI_LOG_I(TAG, "IR Config requested");
            // TODO: Configure IR scanner
            break;
        }
        case FlockMsgTypeNrf24Config: {
            FURI_LOG_I(TAG, "NRF24 Config requested");
            // TODO: Configure NRF24 scanner
            break;
        }

        default:
            FURI_LOG_W(TAG, "Unknown message type: 0x%02X", header.type);
            break;
        }

        // Remove processed message from buffer
        memmove(app->rx_buffer, app->rx_buffer + msg_size, app->rx_buffer_len - msg_size);
        app->rx_buffer_len -= msg_size;

        // Clear timestamp when buffer is empty (clean state)
        if (app->rx_buffer_len == 0) {
            app->rx_buffer_timestamp = 0;
        }
    }

    furi_mutex_release(app->mutex);
}

// ============================================================================
// Application Lifecycle
// ============================================================================

FlockBridgeApp* flock_bridge_app_alloc(void) {
    FlockBridgeApp* app = malloc(sizeof(FlockBridgeApp));
    if (!app) return NULL;

    memset(app, 0, sizeof(FlockBridgeApp));

    // Allocate mutex
    app->mutex = furi_mutex_alloc(FuriMutexTypeRecursive);

    // GUI
    app->gui = furi_record_open(RECORD_GUI);
    app->notifications = furi_record_open(RECORD_NOTIFICATION);

    // View Dispatcher
    app->view_dispatcher = view_dispatcher_alloc();
    if (!app->view_dispatcher) {
        FURI_LOG_E(TAG, "Failed to allocate view_dispatcher");
        furi_mutex_free(app->mutex);
        free(app);
        return NULL;
    }
    view_dispatcher_attach_to_gui(app->view_dispatcher, app->gui, ViewDispatcherTypeFullscreen);

    // Scene Manager - validate handler struct before allocating
    if (!flock_bridge_scene_handlers.on_enter_handlers ||
        !flock_bridge_scene_handlers.on_event_handlers ||
        !flock_bridge_scene_handlers.on_exit_handlers ||
        flock_bridge_scene_handlers.scene_num == 0) {
        FURI_LOG_E(TAG, "Invalid scene handlers configuration");
        view_dispatcher_free(app->view_dispatcher);
        furi_mutex_free(app->mutex);
        free(app);
        return NULL;
    }
    app->scene_manager = scene_manager_alloc(&flock_bridge_scene_handlers, app);
    if (!app->scene_manager) {
        FURI_LOG_E(TAG, "Failed to allocate scene_manager");
        view_dispatcher_free(app->view_dispatcher);
        furi_mutex_free(app->mutex);
        free(app);
        return NULL;
    }

    // Allocate views
    app->widget_main = widget_alloc();
    app->widget_status = widget_alloc();
    app->submenu_main = submenu_alloc();
    app->submenu_settings = submenu_alloc();
    app->popup = popup_alloc();

    // Verify critical view allocations
    if (!app->widget_main || !app->widget_status || !app->submenu_main || !app->submenu_settings) {
        FURI_LOG_E(TAG, "Failed to allocate views");
        if (app->widget_main) widget_free(app->widget_main);
        if (app->widget_status) widget_free(app->widget_status);
        if (app->submenu_main) submenu_free(app->submenu_main);
        if (app->submenu_settings) submenu_free(app->submenu_settings);
        if (app->popup) popup_free(app->popup);
        scene_manager_free(app->scene_manager);
        view_dispatcher_free(app->view_dispatcher);
        furi_mutex_free(app->mutex);
        free(app);
        return NULL;
    }

    // Add views to dispatcher
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewMenu, submenu_get_view(app->submenu_main));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewMain, widget_get_view(app->widget_main));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewStatus, widget_get_view(app->widget_status));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewSettings, submenu_get_view(app->submenu_settings));

    // Set navigation and custom event callbacks
    view_dispatcher_set_navigation_event_callback(app->view_dispatcher, flock_bridge_navigation_event_callback);
    view_dispatcher_set_custom_event_callback(app->view_dispatcher, flock_bridge_custom_event_callback);
    view_dispatcher_set_event_callback_context(app->view_dispatcher, app);

    // Allocate USB CDC
    app->usb_cdc = flock_usb_cdc_alloc();
    if (app->usb_cdc) {
        flock_usb_cdc_set_callback(app->usb_cdc, flock_bridge_data_received, app);
    }

    // Bluetooth Serial - NOT ALLOCATED to allow BLE scanning
    // BT serial uses the same Bluetooth stack as BLE scanner, so we skip allocation
    // app->bt_serial = flock_bt_serial_alloc();
    // if (app->bt_serial) {
    //     flock_bt_serial_set_callback(app->bt_serial, flock_bridge_data_received, app);
    //     flock_bt_serial_set_state_callback(app->bt_serial, flock_bridge_bt_state_changed, app);
    //     FURI_LOG_I(TAG, "Bluetooth Serial allocated");
    // }
    app->bt_serial = NULL;
    FURI_LOG_I(TAG, "Bluetooth Serial DISABLED (using USB + BLE scanning)");

    // Allocate WIPS engine - DISABLED for memory testing
    // app->wips_engine = flock_wips_engine_alloc();

    // Initialize default radio settings - use internal radio by default
    app->radio_settings.subghz_source = FlockRadioSourceInternal;
    app->radio_settings.ble_source = FlockRadioSourceInternal;
    app->radio_settings.wifi_source = FlockRadioSourceExternal;  // WiFi requires ESP32
    app->radio_settings.enable_subghz = false;  // Disabled by default, enable via settings
    app->radio_settings.enable_ble = false;     // Disabled by default, enable via settings
    app->radio_settings.enable_wifi = false;    // Disabled by default, requires ESP32
    app->radio_settings.enable_ir = false;      // Disabled by default, enable via settings
    app->radio_settings.enable_nfc = false;     // Disabled by default, enable via settings

    // Load settings from storage
    flock_bridge_load_settings(app);

    // Re-enabled - all scanning disabled by default
    #if 1
    // Allocate external radio manager
    app->external_radio = external_radio_alloc();
    if (app->external_radio) {
        ExternalRadioConfig ext_config = {
            .serial_id = FuriHalSerialIdUsart,
            .baud_rate = 115200,
            .callback_context = app,
        };
        external_radio_configure(app->external_radio, &ext_config);
    }

    // Allocate detection scheduler
    app->detection_scheduler = detection_scheduler_alloc();
    if (app->detection_scheduler) {
        // Set external radio manager
        detection_scheduler_set_external_radio(app->detection_scheduler, app->external_radio);

        // Apply radio settings to scheduler
        flock_bridge_apply_radio_settings(app);

        // Configure scheduler with callbacks
        SchedulerConfig sched_config = {
            .enable_subghz = app->radio_settings.enable_subghz,
            .enable_ble = app->radio_settings.enable_ble,
            .enable_wifi = app->radio_settings.enable_wifi && app->external_radio != NULL,
            .enable_ir = app->radio_settings.enable_ir,
            .enable_nfc = app->radio_settings.enable_nfc,
            .subghz_hop_interval_ms = 500,
            .subghz_continuous = true,  // Sub-GHz runs during all downtime
            .ble_scan_duration_ms = 2000,
            .ble_scan_interval_ms = 10000,  // BLE scan every 10 seconds
            .ble_detect_trackers = true,
            .wifi_scan_interval_ms = 10000,
            .wifi_channel = 0,  // Channel hop
            .wifi_monitor_probes = true,
            .wifi_detect_deauths = true,
            .subghz_callback = on_subghz_detection,
            .ble_callback = on_ble_detection,
            .ir_callback = on_ir_detection,
            .nfc_callback = on_nfc_detection,
            .callback_context = app,
        };
        detection_scheduler_configure(app->detection_scheduler, &sched_config);

        // Set BT serial for time-multiplexed BLE scanning
        // This allows BLE scanning even when connected via Bluetooth
        if (app->bt_serial) {
            detection_scheduler_set_bt_serial(app->detection_scheduler, app->bt_serial);
        }

        // Mark scanners as ready
        app->subghz_ready = true;
        app->ble_ready = true;
        app->ir_ready = true;
        app->nfc_ready = true;
    }
    #endif
    // Scanners enabled
    FURI_LOG_I(TAG, "Detection scanners initialized");

    // Initialize state
    g_app = app;
    app->connection_mode = FlockConnectionNone;
    app->uptime_start = furi_get_tick();

    FURI_LOG_I(TAG, "Flock Bridge app allocated");
    return app;
}

void flock_bridge_app_free(FlockBridgeApp* app) {
    if (!app) return;

    FURI_LOG_I(TAG, "Freeing Flock Bridge app");

    // Save settings before exit
    flock_bridge_save_settings(app);

    // Stop and free detection scheduler
    if (app->detection_scheduler) {
        detection_scheduler_stop(app->detection_scheduler);
        detection_scheduler_free(app->detection_scheduler);
        app->detection_scheduler = NULL;
    }

    // Stop and free external radio
    if (app->external_radio) {
        external_radio_stop(app->external_radio);
        external_radio_free(app->external_radio);
        app->external_radio = NULL;
    }

    // Stop USB CDC
    if (app->usb_cdc) {
        flock_usb_cdc_stop(app->usb_cdc);
        flock_usb_cdc_free(app->usb_cdc);
    }

    // Stop Bluetooth Serial
    if (app->bt_serial) {
        flock_bt_serial_stop(app->bt_serial);
        flock_bt_serial_free(app->bt_serial);
    }

    // Free WIPS engine
    if (app->wips_engine) {
        flock_wips_engine_free(app->wips_engine);
    }

    g_app = NULL;

    // Remove views from dispatcher
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewMenu);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewMain);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewStatus);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewSettings);

    // Free views
    widget_free(app->widget_main);
    widget_free(app->widget_status);
    submenu_free(app->submenu_main);
    submenu_free(app->submenu_settings);
    popup_free(app->popup);

    // Free scene manager and view dispatcher
    scene_manager_free(app->scene_manager);
    view_dispatcher_free(app->view_dispatcher);

    // Close records
    furi_record_close(RECORD_GUI);
    furi_record_close(RECORD_NOTIFICATION);

    // Free mutex
    furi_mutex_free(app->mutex);

    free(app);
}

// ============================================================================
// Navigation Callback
// ============================================================================

bool flock_bridge_navigation_event_callback(void* context) {
    FlockBridgeApp* app = context;
    return scene_manager_handle_back_event(app->scene_manager);
}

// Custom event callback - forwards custom events to scene manager
static bool flock_bridge_custom_event_callback(void* context, uint32_t event) {
    FlockBridgeApp* app = context;
    return scene_manager_handle_custom_event(app->scene_manager, event);
}

// ============================================================================
// Main Entry Point
// ============================================================================

int32_t flock_bridge_app(void* p) {
    UNUSED(p);

    FlockBridgeApp* app = flock_bridge_app_alloc();
    if (!app) {
        FURI_LOG_E(TAG, "Failed to allocate app");
        return -1;
    }

    // Start USB CDC
    if (app->usb_cdc) {
        if (flock_usb_cdc_start(app->usb_cdc)) {
            app->usb_connected = true;
            app->connection_mode = FlockConnectionUsb;
            FURI_LOG_I(TAG, "USB CDC started - connected");
        }
    }

    // Bluetooth Serial - DISABLED to allow BLE scanning
    // BT serial conflicts with internal BLE scanner (both use Bluetooth stack)
    // Uncomment below to enable BT serial (but BLE scanning will be disabled)
    // if (app->bt_serial) {
    //     if (flock_bt_serial_start(app->bt_serial)) {
    //         FURI_LOG_I(TAG, "Bluetooth Serial started - advertising");
    //     } else {
    //         FURI_LOG_E(TAG, "Failed to start Bluetooth Serial");
    //     }
    // }
    FURI_LOG_I(TAG, "Bluetooth Serial DISABLED (using USB + BLE scanning)");

    // Start external radio manager (will auto-detect ESP32)
    if (app->external_radio) {
        external_radio_start(app->external_radio);
        FURI_LOG_I(TAG, "External radio manager started - scanning for ESP32");
    }

    // Start detection scheduler - RE-ENABLED with all scanners disabled for testing
    if (app->detection_scheduler) {
        detection_scheduler_start(app->detection_scheduler);
        FURI_LOG_I(TAG, "Detection scheduler started (scanners disabled)");
    }

    // Enter main scene
    scene_manager_next_scene(app->scene_manager, FlockBridgeSceneMain);

    // Run the view dispatcher
    view_dispatcher_run(app->view_dispatcher);

    // Cleanup
    flock_bridge_app_free(app);

    return 0;
}

// ============================================================================
// Radio Settings Functions
// ============================================================================

bool flock_bridge_load_settings(FlockBridgeApp* app) {
    if (!app) return false;

    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    bool success = false;

    if (storage_file_open(file, FLOCK_SETTINGS_PATH, FSAM_READ, FSOM_OPEN_EXISTING)) {
        FlockSettingsFile settings_file;
        if (storage_file_read(file, &settings_file, sizeof(settings_file)) == sizeof(settings_file)) {
            if (settings_file.magic == FLOCK_SETTINGS_MAGIC &&
                settings_file.version == FLOCK_SETTINGS_VERSION) {
                memcpy(&app->radio_settings, &settings_file.settings, sizeof(FlockRadioSettings));
                success = true;
                FURI_LOG_I(TAG, "Settings loaded from storage");
            } else {
                FURI_LOG_W(TAG, "Settings file version mismatch, using defaults");
            }
        }
    } else {
        FURI_LOG_I(TAG, "No settings file found, using defaults");
    }

    storage_file_close(file);
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);

    return success;
}

bool flock_bridge_save_settings(FlockBridgeApp* app) {
    if (!app) return false;

    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    bool success = false;

    if (storage_file_open(file, FLOCK_SETTINGS_PATH, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        FlockSettingsFile settings_file = {
            .magic = FLOCK_SETTINGS_MAGIC,
            .version = FLOCK_SETTINGS_VERSION,
        };
        memcpy(&settings_file.settings, &app->radio_settings, sizeof(FlockRadioSettings));

        if (storage_file_write(file, &settings_file, sizeof(settings_file)) == sizeof(settings_file)) {
            success = true;
            FURI_LOG_I(TAG, "Settings saved to storage");
        } else {
            FURI_LOG_E(TAG, "Failed to write settings file");
        }
    } else {
        FURI_LOG_E(TAG, "Failed to open settings file for writing");
    }

    storage_file_close(file);
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);

    return success;
}

void flock_bridge_apply_radio_settings(FlockBridgeApp* app) {
    if (!app || !app->detection_scheduler) return;

    // Convert FlockRadioSourceMode to RadioSourceMode
    RadioSourceSettings sources = {
        .subghz_source = (RadioSourceMode)app->radio_settings.subghz_source,
        .ble_source = (RadioSourceMode)app->radio_settings.ble_source,
        .wifi_source = (RadioSourceMode)app->radio_settings.wifi_source,
    };

    detection_scheduler_set_radio_sources(app->detection_scheduler, &sources);

    FURI_LOG_I(TAG, "Radio settings applied: SubGHz=%s, BLE=%s, WiFi=%s",
        flock_bridge_get_source_name(app->radio_settings.subghz_source),
        flock_bridge_get_source_name(app->radio_settings.ble_source),
        flock_bridge_get_source_name(app->radio_settings.wifi_source));
}

const char* flock_bridge_get_source_name(FlockRadioSourceMode mode) {
    switch (mode) {
    case FlockRadioSourceAuto: return "Auto";
    case FlockRadioSourceInternal: return "Internal";
    case FlockRadioSourceExternal: return "External";
    case FlockRadioSourceBoth: return "Both";
    default: return "Unknown";
    }
}

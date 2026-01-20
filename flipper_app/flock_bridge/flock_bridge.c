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

// ============================================================================
// Scene Handler Declarations
// ============================================================================

// Main scene
static void flock_bridge_scene_main_on_enter(void* context);
static bool flock_bridge_scene_main_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_main_on_exit(void* context);

// Status scene
static void flock_bridge_scene_status_on_enter(void* context);
static bool flock_bridge_scene_status_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_status_on_exit(void* context);

// WiFi Scan scene
static void flock_bridge_scene_wifi_scan_on_enter(void* context);
static bool flock_bridge_scene_wifi_scan_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_wifi_scan_on_exit(void* context);

// SubGHz Scan scene
static void flock_bridge_scene_subghz_scan_on_enter(void* context);
static bool flock_bridge_scene_subghz_scan_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_subghz_scan_on_exit(void* context);

// BLE Scan scene
static void flock_bridge_scene_ble_scan_on_enter(void* context);
static bool flock_bridge_scene_ble_scan_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_ble_scan_on_exit(void* context);

// IR Scan scene
static void flock_bridge_scene_ir_scan_on_enter(void* context);
static bool flock_bridge_scene_ir_scan_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_ir_scan_on_exit(void* context);

// NFC Scan scene
static void flock_bridge_scene_nfc_scan_on_enter(void* context);
static bool flock_bridge_scene_nfc_scan_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_nfc_scan_on_exit(void* context);

// WIPS scene
static void flock_bridge_scene_wips_on_enter(void* context);
static bool flock_bridge_scene_wips_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_wips_on_exit(void* context);

// Settings scene
static void flock_bridge_scene_settings_on_enter(void* context);
static bool flock_bridge_scene_settings_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_settings_on_exit(void* context);

// Connection scene
static void flock_bridge_scene_connection_on_enter(void* context);
static bool flock_bridge_scene_connection_on_event(void* context, SceneManagerEvent event);
static void flock_bridge_scene_connection_on_exit(void* context);

// Event callbacks
static bool flock_bridge_custom_event_callback(void* context, uint32_t event);

// ============================================================================
// Scene Handler Arrays
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

#if 0 // Disabled for memory testing
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

static void flock_bridge_data_received(void* context, uint8_t* data, size_t length) {
    FlockBridgeApp* app = context;
    if (!app || !data || length == 0) return;

    FURI_LOG_I(TAG, "Data callback: %zu bytes received", length);

    // Single blink on data receive
    notification_message(app->notifications, &sequence_blink_blue_10);

    furi_mutex_acquire(app->mutex, FuriWaitForever);

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
    }

    // Process complete messages
    while (app->rx_buffer_len >= FLOCK_HEADER_SIZE) {
        FlockMessageHeader header;
        if (!flock_protocol_parse_header(app->rx_buffer, app->rx_buffer_len, &header)) {
            // Invalid header, discard first byte and try again
            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
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
            continue;
        }

        // Safe calculation - no overflow possible since payload_length <= FLOCK_MAX_PAYLOAD_SIZE
        size_t msg_size = (size_t)FLOCK_HEADER_SIZE + (size_t)header.payload_length;

        if (app->rx_buffer_len < msg_size) {
            // Wait for more data
            break;
        }

        // Handle message
        app->messages_received++;

        switch (header.type) {
        case FlockMsgTypeHeartbeat: {
            size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
            if (len > 0) {
                flock_bridge_send_data(app, app->tx_buffer, len);
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
        case FlockMsgTypeWifiScanRequest:
            // Trigger WiFi scan (would be forwarded to ESP32)
            FURI_LOG_I(TAG, "WiFi scan requested");
            break;
        case FlockMsgTypeSubGhzScanRequest:
            // Trigger Sub-GHz scan
            FURI_LOG_I(TAG, "Sub-GHz scan requested");
            break;
        case FlockMsgTypeBleScanRequest:
            FURI_LOG_I(TAG, "BLE scan requested");
            break;
        case FlockMsgTypeIrScanRequest:
            FURI_LOG_I(TAG, "IR scan requested");
            break;
        case FlockMsgTypeNfcScanRequest:
            FURI_LOG_I(TAG, "NFC scan requested");
            break;

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

                // Initialize RFID hardware for read mode (125kHz carrier)
                furi_hal_rfid_pins_reset();
                furi_hal_rfid_tim_read_start(125000, 0.5f);  // 125kHz, 50% duty cycle

                // Hold carrier for specified duration
                furi_delay_ms(lf_payload.duration_ms);

                // Stop RFID timer and reset pins
                furi_hal_rfid_tim_read_stop();
                furi_hal_rfid_pins_reset();

                FURI_LOG_I(TAG, "LF Probe TX complete");

                // Send success response (heartbeat as acknowledgment)
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
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
                // Opticom uses 10Hz (low priority) or 14Hz (high priority)
                // Typical range: 1-100 Hz for strobe frequency
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

                // Calculate timing for strobe pattern
                // Standard IR carrier: 38kHz
                // Strobe period = 1000ms / frequency_hz
                // On time = period * (duty_cycle / 100)
                const uint32_t ir_carrier_freq = 38000;  // 38kHz standard IR carrier
                float ir_duty_cycle = 0.33f;  // Standard 33% duty for IR carrier
                uint32_t strobe_period_ms = 1000 / ir_payload.frequency_hz;
                uint32_t strobe_on_ms = (strobe_period_ms * ir_payload.duty_cycle) / 100;
                uint32_t strobe_off_ms = strobe_period_ms - strobe_on_ms;
                uint32_t total_cycles = ir_payload.duration_ms / strobe_period_ms;

                FURI_LOG_I(TAG, "IR Strobe: period=%lu ms, on=%lu ms, off=%lu ms, cycles=%lu",
                    strobe_period_ms, strobe_on_ms, strobe_off_ms, total_cycles);

                // Execute strobe pattern
                // For each strobe cycle: turn on IR carrier, wait, turn off, wait
                for (uint32_t cycle = 0; cycle < total_cycles; cycle++) {
                    // Start IR carrier transmission
                    furi_hal_infrared_async_tx_start(ir_carrier_freq, ir_duty_cycle);

                    // Hold carrier on for strobe on-time
                    furi_delay_ms(strobe_on_ms);

                    // Stop IR transmission
                    furi_hal_infrared_async_tx_stop();

                    // Wait for strobe off-time
                    if (strobe_off_ms > 0) {
                        furi_delay_ms(strobe_off_ms);
                    }
                }

                FURI_LOG_I(TAG, "IR Strobe TX complete: %lu cycles", total_cycles);

                // Send success response (heartbeat as acknowledgment)
                size_t len = flock_protocol_create_heartbeat(app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
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



                if (replay_payload.data_len == 0 || replay_payload.data_len > 512 ||
                    replay_payload.repeat_count < 1 || replay_payload.repeat_count > 10) {
                    size_t len = flock_protocol_create_error(
                        FLOCK_ERR_INVALID_PARAM, "SubGHz replay params invalid",
                        app->tx_buffer, sizeof(app->tx_buffer));
                    if (len > 0) {
                        flock_bridge_send_data(app, app->tx_buffer, len);
                    }
                    break;
                }

                // Initialize Sub-GHz hardware
                furi_hal_subghz_init();
                furi_hal_subghz_load_preset(FuriHalSubGhzPresetOok650Async);
                furi_hal_subghz_set_frequency_and_path(replay_payload.frequency);

                FURI_LOG_I(TAG, "SubGHz: transmitting %u bytes, %u times at %lu Hz",
                    replay_payload.data_len, replay_payload.repeat_count, replay_payload.frequency);

                // Transmit the signal the specified number of times
                for (uint8_t repeat = 0; repeat < replay_payload.repeat_count; repeat++) {
                    // Start async TX
                    furi_hal_subghz_start_async_tx(NULL, NULL);  // Would need proper level callback

                    // Wait for transmission (estimate based on data length)
                    uint32_t tx_time_ms = (replay_payload.data_len * 8) / 10 + 50;  // Rough estimate
                    furi_delay_ms(tx_time_ms);

                    // Stop transmission
                    furi_hal_subghz_stop_async_tx();

                    // Brief pause between repeats
                    if (repeat < replay_payload.repeat_count - 1) {
                        furi_delay_ms(100);
                    }
                }

                // Cleanup Sub-GHz
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
                // Track 1: 210 bits per inch, Track 2: 75 bits per inch
                // At ~6 inches/sec swipe speed:
                // Track 1: ~1260 Hz, Track 2: ~450 Hz
                const uint32_t TRACK1_HALF_PERIOD_US = 397;   // ~1260 Hz
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
                        uint8_t ch = mag_payload.track2_data[i];
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

                // Start iButton emulation
                // The Flipper's iButton port uses the 1-Wire protocol
                furi_hal_ibutton_start_worker(iButtonWorkerModeEmulate);

                // Set the key data to emulate
                iButtonKey* key = ibutton_key_alloc();
                ibutton_key_set_protocol_id(key, iButtonProtocolDS1990);
                ibutton_key_set_data(key, ibutton_payload.key_id, 8);

                // Run emulation for the specified duration (default 10 seconds)
                uint32_t duration_ms = ibutton_payload.duration_ms;
                if (duration_ms == 0) duration_ms = 10000;
                if (duration_ms > 60000) duration_ms = 60000;  // Max 60 seconds

                FURI_LOG_I(TAG, "iButton: emulating for %lu ms", duration_ms);

                furi_hal_ibutton_emulate_start(key, NULL);

                // Wait for duration or until contact detected
                furi_delay_ms(duration_ms);

                // Stop emulation
                furi_hal_ibutton_emulate_stop();
                furi_hal_ibutton_stop_worker();
                ibutton_key_free(key);

                FURI_LOG_I(TAG, "iButton Emulate complete");

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
                const uint8_t NRF24_CHANNEL = nrf_payload.channel ? nrf_payload.channel : 5;
                const uint8_t NRF24_PAYLOAD_SIZE = 22;  // Logitech payload size

                FURI_LOG_I(TAG, "NRF24: configuring for channel %u", NRF24_CHANNEL);

                // Initialize NRF24 via SPI on GPIO
                // CE = PA7, CSN = PA4, SCK = PB3, MOSI = PB5, MISO = PB4
                // This uses the external_radio module which handles SPI communication

                // Build the keystroke payloads
                // Logitech Unifying payload format:
                // [00][C1][device_type][key_mods][00][00][key_code][00][checksum]
                for (uint8_t i = 0; i < nrf_payload.keystroke_len; i++) {
                    uint8_t keystroke = nrf_payload.keystrokes[i];
                    uint8_t modifier = nrf_payload.modifiers[i];

                    uint8_t payload[NRF24_PAYLOAD_SIZE];
                    memset(payload, 0, sizeof(payload));

                    // Logitech encrypted keystroke packet
                    payload[0] = 0x00;           // Report type
                    payload[1] = 0xC1;           // Keyboard packet
                    payload[2] = 0x00;           // Device index
                    payload[3] = modifier;       // Modifier keys (Ctrl, Shift, Alt, GUI)
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

                    FURI_LOG_D(TAG, "NRF24: sent key 0x%02X (mod 0x%02X)", keystroke, modifier);

                    // Key press/release timing
                    furi_delay_ms(nrf_payload.delay_ms ? nrf_payload.delay_ms : 10);

                    // Send key release (same payload but with keycode = 0)
                    payload[6] = 0x00;
                    checksum = 0;
                    for (int j = 0; j < NRF24_PAYLOAD_SIZE - 1; j++) {
                        checksum ^= payload[j];
                    }
                    payload[NRF24_PAYLOAD_SIZE - 1] = checksum;

                    // external_radio_nrf24_tx(app->external_radio, nrf_payload.address, payload, NRF24_PAYLOAD_SIZE);

                    // Inter-keystroke delay
                    furi_delay_ms(nrf_payload.delay_ms ? nrf_payload.delay_ms : 50);
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
    view_dispatcher_attach_to_gui(app->view_dispatcher, app->gui, ViewDispatcherTypeFullscreen);

    // Scene Manager
    app->scene_manager = scene_manager_alloc(&flock_bridge_scene_handlers, app);

    // Allocate views
    app->widget_main = widget_alloc();
    app->widget_status = widget_alloc();
    app->submenu = submenu_alloc();
    app->popup = popup_alloc();

    // Add views to dispatcher
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewMain, widget_get_view(app->widget_main));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewStatus, widget_get_view(app->widget_status));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewSettings, submenu_get_view(app->submenu));

    // Set navigation and custom event callbacks
    view_dispatcher_set_navigation_event_callback(app->view_dispatcher, flock_bridge_navigation_event_callback);
    view_dispatcher_set_custom_event_callback(app->view_dispatcher, flock_bridge_custom_event_callback);
    view_dispatcher_set_event_callback_context(app->view_dispatcher, app);

    // Allocate USB CDC
    app->usb_cdc = flock_usb_cdc_alloc();
    if (app->usb_cdc) {
        flock_usb_cdc_set_callback(app->usb_cdc, flock_bridge_data_received, app);
    }

    // Allocate Bluetooth Serial
    app->bt_serial = flock_bt_serial_alloc();
    if (app->bt_serial) {
        flock_bt_serial_set_callback(app->bt_serial, flock_bridge_data_received, app);
        flock_bt_serial_set_state_callback(app->bt_serial, flock_bridge_bt_state_changed, app);
        FURI_LOG_I(TAG, "Bluetooth Serial allocated");
    } else {
        FURI_LOG_E(TAG, "Failed to allocate Bluetooth Serial");
    }

    // Allocate WIPS engine - DISABLED for memory testing
    // app->wips_engine = flock_wips_engine_alloc();

    // Initialize default radio settings
    app->radio_settings.subghz_source = FlockRadioSourceAuto;
    app->radio_settings.ble_source = FlockRadioSourceAuto;
    app->radio_settings.wifi_source = FlockRadioSourceExternal;  // No internal WiFi
    app->radio_settings.enable_subghz = true;
    app->radio_settings.enable_ble = true;
    app->radio_settings.enable_wifi = true;
    app->radio_settings.enable_ir = true;
    app->radio_settings.enable_nfc = true;

    // Load settings from storage
    flock_bridge_load_settings(app);

    // DISABLED FOR MEMORY TESTING - External radio and scanners use too much RAM
    #if 0
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
            .enable_wifi = app->radio_settings.enable_wifi,
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

        // Mark scanners as ready
        app->subghz_ready = true;
        app->ble_ready = true;
        app->ir_ready = true;
        app->nfc_ready = true;
    }
    #endif
    // Scanners disabled - just USB CDC for now
    FURI_LOG_I(TAG, "Scanners disabled for memory testing");

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
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewMain);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewStatus);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewSettings);

    // Free views
    widget_free(app->widget_main);
    widget_free(app->widget_status);
    submenu_free(app->submenu);
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
// Main Scene
// ============================================================================

static void widget_main_input_callback(GuiButtonType result, InputType type, void* context) {
    FlockBridgeApp* app = context;
    if (type == InputTypeShort && result == GuiButtonTypeCenter) {
        // OK pressed - switch to status scene
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneStatus);
    }
}

static void flock_bridge_scene_main_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 5, AlignCenter, AlignTop, FontPrimary, "Flock Bridge");
    widget_add_string_element(app->widget_main, 64, 20, AlignCenter, AlignTop, FontSecondary,
        flock_bridge_get_connection_status(app));
    widget_add_string_element(app->widget_main, 64, 35, AlignCenter, AlignTop, FontSecondary, "Press OK for status");
    widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary, "Hold Back to exit");

    // Add button callback for OK press
    widget_add_button_element(app->widget_main, GuiButtonTypeCenter, "Status", widget_main_input_callback, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_main_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        switch (event.event) {
        // Connection events - green blink
        case FlockBridgeEventUsbConnected:
            app->usb_connected = true;
            flock_bridge_set_connection_mode(app, FlockConnectionUsb);
            notification_message(app->notifications, &sequence_blink_green_100);
            consumed = true;
            break;
        case FlockBridgeEventUsbDisconnected:
            app->usb_connected = false;
            flock_bridge_set_connection_mode(app, FlockConnectionNone);
            notification_message(app->notifications, &sequence_blink_red_100);
            consumed = true;
            break;
        case FlockBridgeEventBtConnected:
            app->bt_connected = true;
            flock_bridge_set_connection_mode(app, FlockConnectionBluetooth);
            notification_message(app->notifications, &sequence_blink_green_100);
            consumed = true;
            break;
        case FlockBridgeEventBtDisconnected:
            app->bt_connected = false;
            flock_bridge_set_connection_mode(app, FlockConnectionNone);
            notification_message(app->notifications, &sequence_blink_red_100);
            consumed = true;
            break;

        // Detection events - yellow blink
        case FlockBridgeEventSubGhzDetection:
            notification_message(app->notifications, &sequence_blink_yellow_10);
            consumed = true;
            break;
        case FlockBridgeEventBleScanComplete:
            notification_message(app->notifications, &sequence_blink_yellow_10);
            consumed = true;
            break;
        case FlockBridgeEventNfcDetection:
            notification_message(app->notifications, &sequence_blink_yellow_10);
            consumed = true;
            break;
        case FlockBridgeEventIrDetection:
            notification_message(app->notifications, &sequence_blink_yellow_10);
            consumed = true;
            break;
        case FlockBridgeEventWifiScanComplete:
            notification_message(app->notifications, &sequence_blink_magenta_10);
            consumed = true;
            break;

        // WIPS alert - red blink with longer duration
        case FlockBridgeEventWipsAlert:
            notification_message(app->notifications, &sequence_blink_red_100);
            consumed = true;
            break;

        default:
            break;
        }
    } else if (event.type == SceneManagerEventTypeBack) {
        // Allow back to exit
    }

    return consumed;
}

static void flock_bridge_scene_main_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// Status Scene
// ============================================================================

// Status refresh interval (500ms for smooth updates)
#define STATUS_REFRESH_INTERVAL_MS 500

// Helper to refresh status widget content
static void flock_bridge_status_refresh(FlockBridgeApp* app) {
    widget_reset(app->widget_status);

    // Title with connection indicator
    const char* conn_status = flock_bridge_get_connection_status(app);
    widget_add_string_element(app->widget_status, 64, 2, AlignCenter, AlignTop, FontPrimary, conn_status);

    char buf[40];

    // Messages sent/received
    snprintf(buf, sizeof(buf), "TX: %lu  RX: %lu", app->messages_sent, app->messages_received);
    widget_add_string_element(app->widget_status, 64, 16, AlignCenter, AlignTop, FontSecondary, buf);

    // Detection counts
    snprintf(buf, sizeof(buf), "SubGHz:%lu BLE:%lu NFC:%lu",
        app->subghz_detection_count, app->ble_scan_count, app->nfc_detection_count);
    widget_add_string_element(app->widget_status, 64, 28, AlignCenter, AlignTop, FontSecondary, buf);

    // WIPS alerts and uptime
    uint32_t uptime_sec = (furi_get_tick() - app->uptime_start) / 1000;
    snprintf(buf, sizeof(buf), "WIPS:%lu  Up:%lus", app->wips_alert_count, uptime_sec);
    widget_add_string_element(app->widget_status, 64, 40, AlignCenter, AlignTop, FontSecondary, buf);

    // WiFi/IR counts
    snprintf(buf, sizeof(buf), "WiFi:%lu IR:%lu", app->wifi_scan_count, app->ir_detection_count);
    widget_add_string_element(app->widget_status, 64, 52, AlignCenter, AlignTop, FontSecondary, buf);
}

// Timer callback for status refresh
static void flock_bridge_status_timer_callback(void* context) {
    FlockBridgeApp* app = context;
    // Send custom event to trigger UI refresh on main thread
    view_dispatcher_send_custom_event(app->view_dispatcher, FlockBridgeEventRefreshStatus);
}

static void flock_bridge_scene_status_on_enter(void* context) {
    FlockBridgeApp* app = context;

    // Initial status refresh
    flock_bridge_status_refresh(app);

    // Create and start the status update timer
    app->status_timer = furi_timer_alloc(flock_bridge_status_timer_callback, FuriTimerTypePeriodic, app);
    furi_timer_start(app->status_timer, furi_ms_to_ticks(STATUS_REFRESH_INTERVAL_MS));

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewStatus);
}

static bool flock_bridge_scene_status_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventRefreshStatus) {
            // Refresh the status display
            flock_bridge_status_refresh(app);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_status_on_exit(void* context) {
    FlockBridgeApp* app = context;

    // Stop and free the timer
    if (app->status_timer) {
        furi_timer_stop(app->status_timer);
        furi_timer_free(app->status_timer);
        app->status_timer = NULL;
    }

    widget_reset(app->widget_status);
}

// ============================================================================
// WiFi Scan Scene
// ============================================================================

static void flock_bridge_scene_wifi_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "WiFi Scanner");

    // Check if ESP32 is connected
    if (app->wifi_board_connected) {
        char buf[40];
        snprintf(buf, sizeof(buf), "Networks: %lu", app->wifi_scan_count);
        widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "ESP32 Connected");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Scanning active...");
    } else {
        widget_add_string_element(app->widget_main, 64, 20, AlignCenter, AlignTop, FontSecondary, "ESP32 Required");
        widget_add_string_element(app->widget_main, 64, 34, AlignCenter, AlignTop, FontSecondary, "Connect WiFi board");
        widget_add_string_element(app->widget_main, 64, 48, AlignCenter, AlignTop, FontSecondary, "to GPIO header");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_wifi_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventWifiScanComplete) {
            // Refresh the display when scan completes
            flock_bridge_scene_wifi_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_cyan_10);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_wifi_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// Sub-GHz Scan Scene
// ============================================================================

static void flock_bridge_scene_subghz_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "Sub-GHz Scanner");

    char buf[40];

    // Show detection count
    snprintf(buf, sizeof(buf), "Detections: %lu", app->subghz_detection_count);
    widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->subghz_ready) {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Scanner Ready");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Freq hopping active");
    } else {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "(Memory limited mode)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_subghz_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventSubGhzDetection) {
            // Refresh display on new detection
            flock_bridge_scene_subghz_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_yellow_10);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_subghz_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// BLE Scan Scene
// ============================================================================

static void flock_bridge_scene_ble_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "BLE Scanner");

    char buf[40];

    // Show device count
    snprintf(buf, sizeof(buf), "Devices: %lu", app->ble_scan_count);
    widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->ble_ready) {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Scanner Ready");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Tracker detection ON");
    } else {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "(BT Serial active)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_ble_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventBleScanComplete) {
            // Refresh display on scan complete
            flock_bridge_scene_ble_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_blue_10);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_ble_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// IR Scan Scene
// ============================================================================

static void flock_bridge_scene_ir_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "IR Scanner");

    char buf[40];

    // Show detection count
    snprintf(buf, sizeof(buf), "Signals: %lu", app->ir_detection_count);
    widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->ir_ready) {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Receiver Active");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Passive monitoring");
    } else {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "(Memory limited mode)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_ir_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventIrDetection) {
            // Refresh display on detection
            flock_bridge_scene_ir_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_ir_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// NFC Scan Scene
// ============================================================================

static void flock_bridge_scene_nfc_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "NFC Scanner");

    char buf[40];

    // Show detection count
    snprintf(buf, sizeof(buf), "Tags: %lu", app->nfc_detection_count);
    widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->nfc_ready) {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Polling Active");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Place tag near device");
    } else {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "(Memory limited mode)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_nfc_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventNfcDetection) {
            // Refresh display on detection
            flock_bridge_scene_nfc_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_green_10);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_nfc_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// WIPS Scene (Wireless Intrusion Prevention)
// ============================================================================

static void flock_bridge_scene_wips_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "WIPS Engine");

    char buf[40];

    // Show alert count
    snprintf(buf, sizeof(buf), "Alerts: %lu", app->wips_alert_count);
    widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);

    // Show WIPS status
    if (app->wips_engine) {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Engine Active");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Evil Twin/Deauth detect");
    } else {
        widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, "Engine Disabled");
        widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, "Requires ESP32 WiFi");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_wips_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventWipsAlert) {
            // Refresh display and alert on WIPS detection
            flock_bridge_scene_wips_on_enter(app);
            notification_message(app->notifications, &sequence_blink_red_100);
            consumed = true;
        }
    }

    return consumed;
}

static void flock_bridge_scene_wips_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

// ============================================================================
// Settings Scene
// ============================================================================

// Submenu callback for settings
static void flock_bridge_settings_submenu_callback(void* context, uint32_t index) {
    FlockBridgeApp* app = context;

    // Toggle settings based on index
    switch (index) {
    case 0: // Sub-GHz
        app->radio_settings.enable_subghz = !app->radio_settings.enable_subghz;
        break;
    case 1: // BLE
        app->radio_settings.enable_ble = !app->radio_settings.enable_ble;
        break;
    case 2: // WiFi
        app->radio_settings.enable_wifi = !app->radio_settings.enable_wifi;
        break;
    case 3: // IR
        app->radio_settings.enable_ir = !app->radio_settings.enable_ir;
        break;
    case 4: // NFC
        app->radio_settings.enable_nfc = !app->radio_settings.enable_nfc;
        break;
    case 5: // Save settings
        flock_bridge_save_settings(app);
        notification_message(app->notifications, &sequence_blink_green_100);
        break;
    }

    // Refresh the menu
    flock_bridge_scene_settings_on_enter(app);
}

static void flock_bridge_scene_settings_on_enter(void* context) {
    FlockBridgeApp* app = context;

    submenu_reset(app->submenu);
    submenu_set_header(app->submenu, "Radio Settings");

    char buf[32];

    // Sub-GHz toggle
    snprintf(buf, sizeof(buf), "Sub-GHz: %s", app->radio_settings.enable_subghz ? "ON" : "OFF");
    submenu_add_item(app->submenu, buf, 0, flock_bridge_settings_submenu_callback, app);

    // BLE toggle
    snprintf(buf, sizeof(buf), "BLE: %s", app->radio_settings.enable_ble ? "ON" : "OFF");
    submenu_add_item(app->submenu, buf, 1, flock_bridge_settings_submenu_callback, app);

    // WiFi toggle
    snprintf(buf, sizeof(buf), "WiFi: %s", app->radio_settings.enable_wifi ? "ON" : "OFF");
    submenu_add_item(app->submenu, buf, 2, flock_bridge_settings_submenu_callback, app);

    // IR toggle
    snprintf(buf, sizeof(buf), "IR: %s", app->radio_settings.enable_ir ? "ON" : "OFF");
    submenu_add_item(app->submenu, buf, 3, flock_bridge_settings_submenu_callback, app);

    // NFC toggle
    snprintf(buf, sizeof(buf), "NFC: %s", app->radio_settings.enable_nfc ? "ON" : "OFF");
    submenu_add_item(app->submenu, buf, 4, flock_bridge_settings_submenu_callback, app);

    // Save option
    submenu_add_item(app->submenu, "Save Settings", 5, flock_bridge_settings_submenu_callback, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewSettings);
}

static bool flock_bridge_scene_settings_on_event(void* context, SceneManagerEvent event) {
    UNUSED(context);
    UNUSED(event);
    return false;
}

static void flock_bridge_scene_settings_on_exit(void* context) {
    FlockBridgeApp* app = context;
    submenu_reset(app->submenu);
}

// ============================================================================
// Connection Scene
// ============================================================================

static void flock_bridge_scene_connection_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 2, AlignCenter, AlignTop, FontPrimary, "Connection");

    // Show current connection mode
    const char* mode_str = "None";
    switch (app->connection_mode) {
    case FlockConnectionBluetooth:
        mode_str = "Bluetooth";
        break;
    case FlockConnectionUsb:
        mode_str = "USB CDC";
        break;
    default:
        mode_str = "Disconnected";
        break;
    }

    char buf[40];
    snprintf(buf, sizeof(buf), "Mode: %s", mode_str);
    widget_add_string_element(app->widget_main, 64, 18, AlignCenter, AlignTop, FontSecondary, buf);

    // Show connection details
    snprintf(buf, sizeof(buf), "USB: %s", app->usb_connected ? "Connected" : "No");
    widget_add_string_element(app->widget_main, 64, 32, AlignCenter, AlignTop, FontSecondary, buf);

    snprintf(buf, sizeof(buf), "BT: %s", app->bt_connected ? "Connected" : "Advertising");
    widget_add_string_element(app->widget_main, 64, 46, AlignCenter, AlignTop, FontSecondary, buf);

    // Show message counts
    snprintf(buf, sizeof(buf), "TX:%lu RX:%lu", app->messages_sent, app->messages_received);
    widget_add_string_element(app->widget_main, 64, 58, AlignCenter, AlignTop, FontSecondary, buf);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

static bool flock_bridge_scene_connection_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        switch (event.event) {
        case FlockBridgeEventUsbConnected:
        case FlockBridgeEventUsbDisconnected:
        case FlockBridgeEventBtConnected:
        case FlockBridgeEventBtDisconnected:
            // Refresh display on connection change
            flock_bridge_scene_connection_on_enter(app);
            consumed = true;
            break;
        default:
            break;
        }
    }

    return consumed;
}

static void flock_bridge_scene_connection_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
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

    // Start Bluetooth Serial
    if (app->bt_serial) {
        if (flock_bt_serial_start(app->bt_serial)) {
            FURI_LOG_I(TAG, "Bluetooth Serial started - advertising");
            // Note: bt_connected will be set to true when a device actually connects
            // via the bt_status_callback in bt_serial.c
        } else {
            FURI_LOG_E(TAG, "Failed to start Bluetooth Serial");
        }
    }

    // Start external radio manager (will auto-detect ESP32)
    if (app->external_radio) {
        external_radio_start(app->external_radio);
        FURI_LOG_I(TAG, "External radio manager started - scanning for ESP32");
    }

    // Start detection scheduler (Sub-GHz continuous, BLE/IR/NFC periodic, WiFi if ESP32 connected)
    if (app->detection_scheduler) {
        detection_scheduler_start(app->detection_scheduler);
        FURI_LOG_I(TAG, "Detection scheduler started - all scanners active");
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

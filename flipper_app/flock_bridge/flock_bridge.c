#include "flock_bridge_app.h"
#include "helpers/wips_engine.h"
#include "helpers/external_radio.h"
#include "protocol/flock_protocol.h"
#include "scanners/detection_scheduler.h"
#include <furi_hal_power.h>
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

// ============================================================================
// Scene Handler Arrays
// ============================================================================

void (*const flock_bridge_scene_on_enter_handlers[])(void*) = {
    [FlockBridgeSceneMain] = flock_bridge_scene_main_on_enter,
    [FlockBridgeSceneStatus] = flock_bridge_scene_status_on_enter,
    [FlockBridgeSceneWifiScan] = flock_bridge_scene_main_on_enter,      // Placeholder
    [FlockBridgeSceneSubGhzScan] = flock_bridge_scene_main_on_enter,   // Placeholder
    [FlockBridgeSceneBleScan] = flock_bridge_scene_main_on_enter,      // Placeholder
    [FlockBridgeSceneIrScan] = flock_bridge_scene_main_on_enter,       // Placeholder
    [FlockBridgeSceneNfcScan] = flock_bridge_scene_main_on_enter,      // Placeholder
    [FlockBridgeSceneWips] = flock_bridge_scene_main_on_enter,         // Placeholder
    [FlockBridgeSceneSettings] = flock_bridge_scene_main_on_enter,     // Placeholder
    [FlockBridgeSceneConnection] = flock_bridge_scene_main_on_enter,   // Placeholder
};

bool (*const flock_bridge_scene_on_event_handlers[])(void*, SceneManagerEvent) = {
    [FlockBridgeSceneMain] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneStatus] = flock_bridge_scene_status_on_event,
    [FlockBridgeSceneWifiScan] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneSubGhzScan] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneBleScan] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneIrScan] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneNfcScan] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneWips] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneSettings] = flock_bridge_scene_main_on_event,
    [FlockBridgeSceneConnection] = flock_bridge_scene_main_on_event,
};

void (*const flock_bridge_scene_on_exit_handlers[])(void*) = {
    [FlockBridgeSceneMain] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneStatus] = flock_bridge_scene_status_on_exit,
    [FlockBridgeSceneWifiScan] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneSubGhzScan] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneBleScan] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneIrScan] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneNfcScan] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneWips] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneSettings] = flock_bridge_scene_main_on_exit,
    [FlockBridgeSceneConnection] = flock_bridge_scene_main_on_exit,
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
            // Tire Kicker - 125kHz LF burst for TPMS wake
            FlockLfProbePayload lf_payload;
            if (flock_protocol_parse_lf_probe(app->rx_buffer, app->rx_buffer_len, &lf_payload)) {
                FURI_LOG_I(TAG, "LF Probe TX: %u ms", lf_payload.duration_ms);
                // TODO: Implement via furi_hal_rfid_tim_read_start()
                // furi_hal_rfid_tim_read_start();
                // furi_delay_ms(lf_payload.duration_ms);
                // furi_hal_rfid_tim_read_stop();

                // Send not-implemented error to Android app
                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_NOT_IMPLEMENTED, "LF probe not yet implemented",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }
        case FlockMsgTypeIrStrobeTx: {
            // Opticom Verifier - IR strobe for traffic preemption
            FlockIrStrobePayload ir_payload;
            if (flock_protocol_parse_ir_strobe(app->rx_buffer, app->rx_buffer_len, &ir_payload)) {
                FURI_LOG_I(TAG, "IR Strobe TX: %u Hz, %u%% duty, %u ms",
                    ir_payload.frequency_hz, ir_payload.duty_cycle, ir_payload.duration_ms);
                // TODO: Implement via furi_hal_infrared_async_tx_start()

                size_t len = flock_protocol_create_error(
                    FLOCK_ERR_NOT_IMPLEMENTED, "IR strobe not yet implemented",
                    app->tx_buffer, sizeof(app->tx_buffer));
                if (len > 0) {
                    flock_bridge_send_data(app, app->tx_buffer, len);
                }
            }
            break;
        }
        case FlockMsgTypeWifiProbeTx: {
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
            // Zigbee Knocker - Forward to ESP32
            // Note: parsing function would need to be added
            FURI_LOG_I(TAG, "Zigbee Beacon TX requested");
            // Forward to ESP32 via UART
            break;
        }
        case FlockMsgTypeGpioPulseTx: {
            // Ghost Car - GPIO coil pulse for inductive loop
            FlockGpioPulsePayload gpio_payload;
            if (flock_protocol_parse_gpio_pulse(app->rx_buffer, app->rx_buffer_len, &gpio_payload)) {
                FURI_LOG_I(TAG, "GPIO Pulse TX: %lu Hz, %u ms, %u pulses",
                    gpio_payload.frequency_hz, gpio_payload.duration_ms, gpio_payload.pulse_count);
                // TODO: Implement via furi_hal_gpio
            }
            break;
        }

        // ================================================================
        // Active Probe Commands - Physical Access
        // ================================================================
        case FlockMsgTypeSubGhzReplayTx: {
            // Sleep Denial - Sub-GHz signal replay
            FlockSubGhzReplayPayload replay_payload;
            if (flock_protocol_parse_subghz_replay(app->rx_buffer, app->rx_buffer_len, &replay_payload)) {
                FURI_LOG_I(TAG, "SubGHz Replay TX: %lu Hz, %u bytes, %u repeats",
                    replay_payload.frequency, replay_payload.data_len, replay_payload.repeat_count);
                // TODO: Implement via subghz_tx
            }
            break;
        }
        case FlockMsgTypeWiegandReplayTx: {
            // Replay Injector - Wiegand card replay
            FlockWiegandReplayPayload wiegand_payload;
            if (flock_protocol_parse_wiegand_replay(app->rx_buffer, app->rx_buffer_len, &wiegand_payload)) {
                FURI_LOG_I(TAG, "Wiegand Replay TX: FC=%lu, CN=%lu, %u-bit",
                    wiegand_payload.facility_code, wiegand_payload.card_number, wiegand_payload.bit_length);
                // TODO: Implement via GPIO D0/D1 pulses
            }
            break;
        }
        case FlockMsgTypeMagSpoofTx: {
            // MagSpoof - Magstripe emulation
            FlockMagSpoofPayload mag_payload;
            if (flock_protocol_parse_magspoof(app->rx_buffer, app->rx_buffer_len, &mag_payload)) {
                FURI_LOG_I(TAG, "MagSpoof TX: T1=%u bytes, T2=%u bytes",
                    mag_payload.track1_len, mag_payload.track2_len);
                // TODO: Implement via GPIO coil pulses
            }
            break;
        }
        case FlockMsgTypeIButtonEmulate: {
            // Master Key - iButton emulation
            FlockIButtonPayload ibutton_payload;
            if (flock_protocol_parse_ibutton(app->rx_buffer, app->rx_buffer_len, &ibutton_payload)) {
                FURI_LOG_I(TAG, "iButton Emulate: %02X:%02X:%02X:%02X:%02X:%02X:%02X:%02X",
                    ibutton_payload.key_id[0], ibutton_payload.key_id[1],
                    ibutton_payload.key_id[2], ibutton_payload.key_id[3],
                    ibutton_payload.key_id[4], ibutton_payload.key_id[5],
                    ibutton_payload.key_id[6], ibutton_payload.key_id[7]);
                // TODO: Implement via furi_hal_ibutton
            }
            break;
        }

        // ================================================================
        // Active Probe Commands - Digital
        // ================================================================
        case FlockMsgTypeNrf24InjectTx: {
            // MouseJacker - NRF24 keystroke injection
            FlockNrf24InjectPayload nrf_payload;
            if (flock_protocol_parse_nrf24_inject(app->rx_buffer, app->rx_buffer_len, &nrf_payload)) {
                FURI_LOG_I(TAG, "NRF24 Inject TX: addr=%02X:%02X:%02X:%02X:%02X, %u keystrokes",
                    nrf_payload.address[0], nrf_payload.address[1], nrf_payload.address[2],
                    nrf_payload.address[3], nrf_payload.address[4], nrf_payload.keystroke_len);
                // TODO: Implement via NRF24 module on GPIO
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

    // Set navigation callback
    view_dispatcher_set_navigation_event_callback(app->view_dispatcher, flock_bridge_navigation_event_callback);
    view_dispatcher_set_event_callback_context(app->view_dispatcher, app);

    // Allocate USB CDC
    app->usb_cdc = flock_usb_cdc_alloc();
    if (app->usb_cdc) {
        flock_usb_cdc_set_callback(app->usb_cdc, flock_bridge_data_received, app);
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

    // Free WIPS engine
    if (app->wips_engine) {
        flock_wips_engine_free(app->wips_engine);
    }

    g_app = NULL;

    // Remove views from dispatcher
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewMain);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewStatus);

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
        case FlockBridgeEventUsbConnected:
            app->usb_connected = true;
            flock_bridge_set_connection_mode(app, FlockConnectionUsb);
            notification_message(app->notifications, &sequence_success);
            consumed = true;
            break;
        case FlockBridgeEventUsbDisconnected:
            app->usb_connected = false;
            flock_bridge_set_connection_mode(app, FlockConnectionNone);
            consumed = true;
            break;
        case FlockBridgeEventBtConnected:
            app->bt_connected = true;
            flock_bridge_set_connection_mode(app, FlockConnectionBluetooth);
            notification_message(app->notifications, &sequence_success);
            consumed = true;
            break;
        case FlockBridgeEventBtDisconnected:
            app->bt_connected = false;
            flock_bridge_set_connection_mode(app, FlockConnectionNone);
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

static void flock_bridge_scene_status_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_status);
    widget_add_string_element(app->widget_status, 64, 5, AlignCenter, AlignTop, FontPrimary, "Status");

    char buf[32];
    snprintf(buf, sizeof(buf), "Sent: %lu  Recv: %lu", app->messages_sent, app->messages_received);
    widget_add_string_element(app->widget_status, 64, 20, AlignCenter, AlignTop, FontSecondary, buf);

    snprintf(buf, sizeof(buf), "WIPS Alerts: %lu", app->wips_alert_count);
    widget_add_string_element(app->widget_status, 64, 35, AlignCenter, AlignTop, FontSecondary, buf);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewStatus);
}

static bool flock_bridge_scene_status_on_event(void* context, SceneManagerEvent event) {
    UNUSED(context);
    UNUSED(event);
    return false;
}

static void flock_bridge_scene_status_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_status);
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

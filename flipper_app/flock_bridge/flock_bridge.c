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

static FlockBridgeApp* g_app = NULL;  // For callbacks

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

    // Serialize and send (would need to add serialization function)
    // For now just increment counter

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

    // Serialize and send (would need to add serialization function)

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

    // Serialize and send (would need to add serialization function)

    furi_mutex_release(app->mutex);
}

// ============================================================================
// Data Callback
// ============================================================================

static void flock_bridge_data_received(void* context, uint8_t* data, size_t length) {
    FlockBridgeApp* app = context;
    if (!app || !data || length == 0) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    // Append to rx_buffer
    size_t space = sizeof(app->rx_buffer) - app->rx_buffer_len;
    size_t to_copy = length < space ? length : space;
    memcpy(app->rx_buffer + app->rx_buffer_len, data, to_copy);
    app->rx_buffer_len += to_copy;

    // Process complete messages
    while (app->rx_buffer_len >= 4) {
        FlockMessageHeader header;
        if (!flock_protocol_parse_header(app->rx_buffer, app->rx_buffer_len, &header)) {
            // Invalid header, discard first byte and try again
            memmove(app->rx_buffer, app->rx_buffer + 1, app->rx_buffer_len - 1);
            app->rx_buffer_len--;
            continue;
        }

        size_t msg_size = 4 + header.payload_length;
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
    view_dispatcher_enable_queue(app->view_dispatcher);
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

    // Allocate WIPS engine
    app->wips_engine = flock_wips_engine_alloc();

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

static void flock_bridge_scene_main_on_enter(void* context) {
    FlockBridgeApp* app = context;

    widget_reset(app->widget_main);
    widget_add_string_element(app->widget_main, 64, 5, AlignCenter, AlignTop, FontPrimary, "Flock Bridge");
    widget_add_string_element(app->widget_main, 64, 20, AlignCenter, AlignTop, FontSecondary,
        flock_bridge_get_connection_status(app));
    widget_add_string_element(app->widget_main, 64, 35, AlignCenter, AlignTop, FontSecondary, "Press OK for status");
    widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary, "Hold Back to exit");

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
        flock_usb_cdc_start(app->usb_cdc);
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

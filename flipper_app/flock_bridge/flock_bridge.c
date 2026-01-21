/**
 * Flock Bridge - Main Application
 *
 * Core application lifecycle, scene management, and entry point.
 * Message handling, detection callbacks, and settings are in handlers/.
 */

#include "flock_bridge_app.h"
#include "handlers/handlers.h"
#include "helpers/bt_serial.h"
#include "helpers/wips_engine.h"
#include "helpers/external_radio.h"
#include "protocol/flock_protocol.h"
#include "scanners/detection_scheduler.h"
#include <furi_hal_power.h>

#define TAG "FlockBridge"

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
        if (!app->usb_connected) {
            app->connection_mode = FlockConnectionBluetooth;
        }
        notification_message(app->notifications, &sequence_blink_green_100);
    } else {
        FURI_LOG_I(TAG, "Bluetooth device disconnected");
        if (app->connection_mode == FlockConnectionBluetooth) {
            app->connection_mode = app->usb_connected ? FlockConnectionUsb : FlockConnectionNone;
        }
        notification_message(app->notifications, &sequence_blink_red_100);
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

    // Scene Manager
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

    // Add views to dispatcher - all declared view IDs must be registered
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewMenu, submenu_get_view(app->submenu_main));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewMain, widget_get_view(app->widget_main));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewStatus, widget_get_view(app->widget_status));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewSettings, submenu_get_view(app->submenu_settings));
    view_dispatcher_add_view(app->view_dispatcher, FlockBridgeViewPopup, popup_get_view(app->popup));

    // Set navigation and custom event callbacks
    view_dispatcher_set_navigation_event_callback(app->view_dispatcher, flock_bridge_navigation_event_callback);
    view_dispatcher_set_custom_event_callback(app->view_dispatcher, flock_bridge_custom_event_callback);
    view_dispatcher_set_event_callback_context(app->view_dispatcher, app);

    // Allocate USB CDC
    app->usb_cdc = flock_usb_cdc_alloc();
    if (app->usb_cdc) {
        flock_usb_cdc_set_callback(app->usb_cdc, flock_bridge_data_received, app);
    }

    // Bluetooth Serial - DISABLED to allow BLE scanning
    app->bt_serial = NULL;
    FURI_LOG_I(TAG, "Bluetooth Serial DISABLED (using USB + BLE scanning)");

    // Initialize default radio settings
    app->radio_settings.subghz_source = FlockRadioSourceInternal;
    app->radio_settings.ble_source = FlockRadioSourceInternal;
    app->radio_settings.wifi_source = FlockRadioSourceExternal;
    app->radio_settings.enable_subghz = false;
    app->radio_settings.enable_ble = false;
    app->radio_settings.enable_wifi = false;
    app->radio_settings.enable_ir = false;
    app->radio_settings.enable_nfc = false;

    // Load settings from storage
    flock_bridge_load_settings(app);

    // Check if any scanners are enabled
    bool any_scanner_enabled = app->radio_settings.enable_subghz ||
                               app->radio_settings.enable_ble ||
                               app->radio_settings.enable_wifi ||
                               app->radio_settings.enable_ir ||
                               app->radio_settings.enable_nfc;

    // Only allocate detection scheduler if at least one scanner is enabled
    if (any_scanner_enabled) {
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
            detection_scheduler_set_external_radio(app->detection_scheduler, app->external_radio);
            flock_bridge_apply_radio_settings(app);

            SchedulerConfig sched_config = {
                .enable_subghz = app->radio_settings.enable_subghz,
                .enable_ble = app->radio_settings.enable_ble,
                .enable_wifi = app->radio_settings.enable_wifi && app->external_radio != NULL,
                .enable_ir = app->radio_settings.enable_ir,
                .enable_nfc = app->radio_settings.enable_nfc,
                .subghz_hop_interval_ms = 500,
                .subghz_continuous = true,
                .ble_scan_duration_ms = 2000,
                .ble_scan_interval_ms = 10000,
                .ble_detect_trackers = true,
                .wifi_scan_interval_ms = 10000,
                .wifi_channel = 0,
                .wifi_monitor_probes = true,
                .wifi_detect_deauths = true,
                .subghz_callback = on_subghz_detection,
                .ble_callback = on_ble_detection,
                .ir_callback = on_ir_detection,
                .nfc_callback = on_nfc_detection,
                .callback_context = app,
            };
            detection_scheduler_configure(app->detection_scheduler, &sched_config);

            if (app->bt_serial) {
                detection_scheduler_set_bt_serial(app->detection_scheduler, app->bt_serial);
            }

            app->subghz_ready = true;
            app->ble_ready = true;
            app->ir_ready = true;
            app->nfc_ready = true;
        }
        FURI_LOG_I(TAG, "Detection scanners initialized");
    } else {
        FURI_LOG_I(TAG, "All scanners disabled - skipping detection scheduler allocation");
    }

    // Initialize state
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

    // Remove views from dispatcher - must match all registered views
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewMenu);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewMain);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewStatus);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewSettings);
    view_dispatcher_remove_view(app->view_dispatcher, FlockBridgeViewPopup);

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

    FURI_LOG_I(TAG, "Bluetooth Serial DISABLED (using USB + BLE scanning)");

    // Start external radio manager
    if (app->external_radio) {
        external_radio_start(app->external_radio);
        FURI_LOG_I(TAG, "External radio manager started - scanning for ESP32");
    }

    // Start detection scheduler
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

#include "scenes.h"
#include <furi_hal_power.h>

#define TAG "SceneMain"

// ============================================================================
// Main Scene - Navigation Menu
// ============================================================================

// Menu item indices for main menu
typedef enum {
    MainMenuItemStatus,
    MainMenuItemSubGhz,
    MainMenuItemBle,
    MainMenuItemWifi,
    MainMenuItemIr,
    MainMenuItemNfc,
    MainMenuItemWips,
    MainMenuItemConnection,
    MainMenuItemSettings,
} MainMenuItem;

static void flock_bridge_main_menu_callback(void* context, uint32_t index) {
    FlockBridgeApp* app = context;

    switch (index) {
    case MainMenuItemStatus:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneStatus);
        break;
    case MainMenuItemSubGhz:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneSubGhzScan);
        break;
    case MainMenuItemBle:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneBleScan);
        break;
    case MainMenuItemWifi:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneWifiScan);
        break;
    case MainMenuItemIr:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneIrScan);
        break;
    case MainMenuItemNfc:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneNfcScan);
        break;
    case MainMenuItemWips:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneWips);
        break;
    case MainMenuItemConnection:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneConnection);
        break;
    case MainMenuItemSettings:
        scene_manager_next_scene(app->scene_manager, FlockBridgeSceneSettings);
        break;
    default:
        break;
    }
}

void flock_bridge_scene_main_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    submenu_reset(app->submenu_main);

    // Header with connection and battery status
    snprintf(buf, sizeof(buf), "Flock Bridge [%s] %d%%",
        (app->usb_connected || app->bt_connected) ? "OK" : "--",
        furi_hal_power_get_pct());
    submenu_set_header(app->submenu_main, buf);

    // Status - with detection count summary
    uint32_t total_det = app->subghz_detection_count + app->ble_scan_count +
                         app->nfc_detection_count + app->ir_detection_count;
    snprintf(buf, sizeof(buf), "Status (%lu detections)", total_det);
    submenu_add_item(app->submenu_main, buf, MainMenuItemStatus, flock_bridge_main_menu_callback, app);

    // Sub-GHz scanner with status
    snprintf(buf, sizeof(buf), "Sub-GHz  [%s] %lu",
        app->radio_settings.enable_subghz ? "ON" : "--",
        app->subghz_detection_count);
    submenu_add_item(app->submenu_main, buf, MainMenuItemSubGhz, flock_bridge_main_menu_callback, app);

    // BLE scanner with status
    snprintf(buf, sizeof(buf), "BLE Scan [%s] %lu",
        app->radio_settings.enable_ble ? "ON" : "--",
        app->ble_scan_count);
    submenu_add_item(app->submenu_main, buf, MainMenuItemBle, flock_bridge_main_menu_callback, app);

    // WiFi scanner with status
    snprintf(buf, sizeof(buf), "WiFi     [%s] %lu",
        app->radio_settings.enable_wifi ? "ON" : "--",
        app->wifi_scan_count);
    submenu_add_item(app->submenu_main, buf, MainMenuItemWifi, flock_bridge_main_menu_callback, app);

    // IR scanner with status
    snprintf(buf, sizeof(buf), "IR Scan  [%s] %lu",
        app->radio_settings.enable_ir ? "ON" : "--",
        app->ir_detection_count);
    submenu_add_item(app->submenu_main, buf, MainMenuItemIr, flock_bridge_main_menu_callback, app);

    // NFC scanner with status
    snprintf(buf, sizeof(buf), "NFC Scan [%s] %lu",
        app->radio_settings.enable_nfc ? "ON" : "--",
        app->nfc_detection_count);
    submenu_add_item(app->submenu_main, buf, MainMenuItemNfc, flock_bridge_main_menu_callback, app);

    // WIPS alerts with count
    snprintf(buf, sizeof(buf), "WIPS Monitor (%lu alerts)", app->wips_alert_count);
    submenu_add_item(app->submenu_main, buf, MainMenuItemWips, flock_bridge_main_menu_callback, app);

    // Connection status
    const char* conn_mode = "None";
    if (app->bt_connected) conn_mode = "Bluetooth";
    else if (app->usb_connected) conn_mode = "USB";
    snprintf(buf, sizeof(buf), "Connection [%s]", conn_mode);
    submenu_add_item(app->submenu_main, buf, MainMenuItemConnection, flock_bridge_main_menu_callback, app);

    // Settings
    submenu_add_item(app->submenu_main, "Settings", MainMenuItemSettings, flock_bridge_main_menu_callback, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMenu);
}

bool flock_bridge_scene_main_on_event(void* context, SceneManagerEvent event) {
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

        // WIPS alert - red blink with haptic feedback for security alerts
        case FlockBridgeEventWipsAlert:
            notification_message(app->notifications, &sequence_blink_red_100);
            notification_message(app->notifications, &sequence_double_vibro);
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

void flock_bridge_scene_main_on_exit(void* context) {
    FlockBridgeApp* app = context;
    submenu_reset(app->submenu_main);
}

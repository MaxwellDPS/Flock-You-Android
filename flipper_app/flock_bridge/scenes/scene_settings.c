#include "scenes.h"
#include "../helpers/external_radio.h"

#define TAG "SceneSettings"

// Menu item indices
enum SettingsMenuIndex {
    SettingsMenuExtRadio = 0,
    SettingsMenuSubGhz,
    SettingsMenuBle,
    SettingsMenuWifi,
    SettingsMenuIr,
    SettingsMenuNfc,
    SettingsMenuSave,
};

// ============================================================================
// Settings Scene
// ============================================================================

// Check if external radio (ESP32) is connected
static bool is_esp32_connected(FlockBridgeApp* app) {
    return app->external_radio && external_radio_is_connected(app->external_radio);
}

// Submenu callback for settings
static void flock_bridge_settings_submenu_callback(void* context, uint32_t index) {
    FlockBridgeApp* app = context;

    // Toggle settings based on index
    switch (index) {
    case SettingsMenuExtRadio:
        // Info only - no action, or could trigger a rescan
        notification_message(app->notifications, &sequence_blink_blue_10);
        break;
    case SettingsMenuSubGhz:
        app->radio_settings.enable_subghz = !app->radio_settings.enable_subghz;
        break;
    case SettingsMenuBle:
        app->radio_settings.enable_ble = !app->radio_settings.enable_ble;
        break;
    case SettingsMenuWifi:
        // Only allow WiFi toggle if ESP32 is connected
        if (is_esp32_connected(app)) {
            app->radio_settings.enable_wifi = !app->radio_settings.enable_wifi;
        } else {
            // Show error - ESP32 required
            notification_message(app->notifications, &sequence_error);
        }
        break;
    case SettingsMenuIr:
        app->radio_settings.enable_ir = !app->radio_settings.enable_ir;
        break;
    case SettingsMenuNfc:
        app->radio_settings.enable_nfc = !app->radio_settings.enable_nfc;
        break;
    case SettingsMenuSave:
        flock_bridge_save_settings(app);
        notification_message(app->notifications, &sequence_blink_green_100);
        break;
    }

    // Refresh the menu
    flock_bridge_scene_settings_on_enter(app);
}

void flock_bridge_scene_settings_on_enter(void* context) {
    FlockBridgeApp* app = context;

    submenu_reset(app->submenu_settings);
    submenu_set_header(app->submenu_settings, "Radio Settings");

    char buf[40];
    bool esp32_connected = is_esp32_connected(app);

    // ESP32/External Radio status (info item)
    snprintf(buf, sizeof(buf), "ESP32: %s", esp32_connected ? "Connected" : "Not Found");
    submenu_add_item(app->submenu_settings, buf, SettingsMenuExtRadio, flock_bridge_settings_submenu_callback, app);

    // Sub-GHz toggle
    snprintf(buf, sizeof(buf), "Sub-GHz: %s", app->radio_settings.enable_subghz ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, SettingsMenuSubGhz, flock_bridge_settings_submenu_callback, app);

    // BLE toggle
    snprintf(buf, sizeof(buf), "BLE: %s", app->radio_settings.enable_ble ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, SettingsMenuBle, flock_bridge_settings_submenu_callback, app);

    // WiFi toggle - show status and requirement
    if (esp32_connected) {
        snprintf(buf, sizeof(buf), "WiFi: %s", app->radio_settings.enable_wifi ? "ON" : "OFF");
    } else {
        snprintf(buf, sizeof(buf), "WiFi: -- (No ESP32)");
        // Force WiFi off if ESP32 not connected
        app->radio_settings.enable_wifi = false;
    }
    submenu_add_item(app->submenu_settings, buf, SettingsMenuWifi, flock_bridge_settings_submenu_callback, app);

    // IR toggle
    snprintf(buf, sizeof(buf), "IR: %s", app->radio_settings.enable_ir ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, SettingsMenuIr, flock_bridge_settings_submenu_callback, app);

    // NFC toggle
    snprintf(buf, sizeof(buf), "NFC: %s", app->radio_settings.enable_nfc ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, SettingsMenuNfc, flock_bridge_settings_submenu_callback, app);

    // Save option
    submenu_add_item(app->submenu_settings, "Save Settings", SettingsMenuSave, flock_bridge_settings_submenu_callback, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewSettings);
}

bool flock_bridge_scene_settings_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        // Handle custom events (e.g., external radio connection changes)
        switch (event.event) {
        case FlockBridgeEventBtConnected:
        case FlockBridgeEventBtDisconnected:
            // Refresh menu to update any BLE-related status
            flock_bridge_scene_settings_on_enter(app);
            consumed = true;
            break;
        default:
            break;
        }
    } else if (event.type == SceneManagerEventTypeBack) {
        // Apply settings to the detection scheduler when leaving settings
        flock_bridge_apply_radio_settings(app);
        // Don't consume - let scene manager handle back navigation
    }

    return consumed;
}

void flock_bridge_scene_settings_on_exit(void* context) {
    FlockBridgeApp* app = context;
    submenu_reset(app->submenu_settings);
}

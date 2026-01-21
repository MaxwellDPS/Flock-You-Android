#include "scenes.h"

#define TAG "SceneSettings"

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

void flock_bridge_scene_settings_on_enter(void* context) {
    FlockBridgeApp* app = context;

    submenu_reset(app->submenu_settings);
    submenu_set_header(app->submenu_settings, "Radio Settings");

    char buf[32];

    // Sub-GHz toggle
    snprintf(buf, sizeof(buf), "Sub-GHz: %s", app->radio_settings.enable_subghz ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, 0, flock_bridge_settings_submenu_callback, app);

    // BLE toggle
    snprintf(buf, sizeof(buf), "BLE: %s", app->radio_settings.enable_ble ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, 1, flock_bridge_settings_submenu_callback, app);

    // WiFi toggle
    snprintf(buf, sizeof(buf), "WiFi: %s", app->radio_settings.enable_wifi ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, 2, flock_bridge_settings_submenu_callback, app);

    // IR toggle
    snprintf(buf, sizeof(buf), "IR: %s", app->radio_settings.enable_ir ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, 3, flock_bridge_settings_submenu_callback, app);

    // NFC toggle
    snprintf(buf, sizeof(buf), "NFC: %s", app->radio_settings.enable_nfc ? "ON" : "OFF");
    submenu_add_item(app->submenu_settings, buf, 4, flock_bridge_settings_submenu_callback, app);

    // Save option
    submenu_add_item(app->submenu_settings, "Save Settings", 5, flock_bridge_settings_submenu_callback, app);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewSettings);
}

bool flock_bridge_scene_settings_on_event(void* context, SceneManagerEvent event) {
    UNUSED(context);
    UNUSED(event);
    return false;
}

void flock_bridge_scene_settings_on_exit(void* context) {
    FlockBridgeApp* app = context;
    submenu_reset(app->submenu_settings);
}

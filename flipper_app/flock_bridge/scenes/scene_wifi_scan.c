#include "scenes.h"

#define TAG "SceneWifiScan"

// ============================================================================
// WiFi Scan Scene
// ============================================================================

void flock_bridge_scene_wifi_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with status indicator
    snprintf(buf, sizeof(buf), "WiFi Scanner [%s]",
        app->wifi_board_connected ? "ON" : "OFF");
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // Check if ESP32 is connected
    if (app->wifi_board_connected) {
        snprintf(buf, sizeof(buf), "Networks Found: %lu", app->wifi_scan_count);
        widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "ESP32 Board: Connected");

        // Show WIPS integration status
        snprintf(buf, sizeof(buf), "WIPS Alerts: %lu", app->wips_alert_count);
        widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary, buf);

        widget_add_string_element(app->widget_main, 64, 52, AlignCenter, AlignTop, FontSecondary,
            "Scanning 2.4/5GHz...");
    } else {
        widget_add_string_element(app->widget_main, 64, 16, AlignCenter, AlignTop, FontSecondary,
            "ESP32 Board Required");
        widget_add_string_element(app->widget_main, 64, 30, AlignCenter, AlignTop, FontSecondary,
            "Connect WiFi Dev Board");
        widget_add_string_element(app->widget_main, 64, 44, AlignCenter, AlignTop, FontSecondary,
            "to GPIO header pins");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_wifi_scan_on_event(void* context, SceneManagerEvent event) {
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

void flock_bridge_scene_wifi_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

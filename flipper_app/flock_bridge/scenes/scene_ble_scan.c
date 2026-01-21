#include "scenes.h"

#define TAG "SceneBleScan"

// ============================================================================
// BLE Scan Scene
// ============================================================================

void flock_bridge_scene_ble_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with status indicator
    snprintf(buf, sizeof(buf), "BLE Scanner [%s]", app->ble_ready ? "ON" : "OFF");
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // Show device count prominently
    snprintf(buf, sizeof(buf), "Devices Found: %lu", app->ble_scan_count);
    widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->ble_ready) {
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "BLE Radio: Scanning");

        // Show what we're detecting
        widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary,
            "AirTag/Tile/SmartTag");

        // Connection status
        snprintf(buf, sizeof(buf), "BT Serial: %s",
            app->bt_connected ? "In Use" : "Available");
        widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary, buf);
    } else {
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "Scanner Paused");
        widget_add_string_element(app->widget_main, 64, 40, AlignCenter, AlignTop, FontSecondary,
            "(BT Serial connection active)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_ble_scan_on_event(void* context, SceneManagerEvent event) {
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

void flock_bridge_scene_ble_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

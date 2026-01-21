#include "scenes.h"

#define TAG "SceneConnection"

// ============================================================================
// Connection Scene
// ============================================================================

void flock_bridge_scene_connection_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with active mode
    const char* mode_str = "None";
    switch (app->connection_mode) {
    case FlockConnectionBluetooth:
        mode_str = "BT";
        break;
    case FlockConnectionUsb:
        mode_str = "USB";
        break;
    default:
        mode_str = "--";
        break;
    }
    snprintf(buf, sizeof(buf), "Connection [%s]", mode_str);
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // USB connection status
    snprintf(buf, sizeof(buf), "USB CDC: %s",
        app->usb_connected ? "CONNECTED" : "Not connected");
    widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

    // Bluetooth connection status
    snprintf(buf, sizeof(buf), "Bluetooth: %s",
        app->bt_connected ? "CONNECTED" : "Advertising...");
    widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary, buf);

    // External radio status
    snprintf(buf, sizeof(buf), "Ext Radio: %s",
        app->external_radio_connected ? "CONNECTED" : "None");
    widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary, buf);

    // Message throughput
    snprintf(buf, sizeof(buf), "TX:%lu  RX:%lu msgs",
        app->messages_sent, app->messages_received);
    widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary, buf);

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_connection_on_event(void* context, SceneManagerEvent event) {
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

void flock_bridge_scene_connection_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

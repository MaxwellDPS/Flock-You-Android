#include "scenes.h"

#define TAG "SceneSubGhzScan"

// ============================================================================
// Sub-GHz Scan Scene
// ============================================================================

void flock_bridge_scene_subghz_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with status indicator
    snprintf(buf, sizeof(buf), "Sub-GHz [%s]", app->subghz_ready ? "ON" : "OFF");
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // Show detection count prominently
    snprintf(buf, sizeof(buf), "RF Detections: %lu", app->subghz_detection_count);
    widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->subghz_ready) {
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "CC1101 Radio: Active");

        // Show frequency bands being scanned
        widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary,
            "300-928MHz Hopping");

        // Show external radio status if applicable
        snprintf(buf, sizeof(buf), "Ext Radio: %s",
            app->external_radio_connected ? "Connected" : "None");
        widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary, buf);
    } else {
        widget_add_string_element(app->widget_main, 64, 28, AlignCenter, AlignTop, FontSecondary,
            "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 42, AlignCenter, AlignTop, FontSecondary,
            "(Low memory mode)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_subghz_scan_on_event(void* context, SceneManagerEvent event) {
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

void flock_bridge_scene_subghz_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

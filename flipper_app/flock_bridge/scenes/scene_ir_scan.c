#include "scenes.h"

#define TAG "SceneIrScan"

// ============================================================================
// IR Scan Scene
// ============================================================================

void flock_bridge_scene_ir_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with status indicator
    snprintf(buf, sizeof(buf), "IR Scanner [%s]", app->ir_ready ? "ON" : "OFF");
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // Show detection count prominently
    snprintf(buf, sizeof(buf), "IR Signals: %lu", app->ir_detection_count);
    widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->ir_ready) {
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "TSOP Receiver: Active");

        // Show what protocols we detect
        widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary,
            "NEC/RC5/RC6/SIRC/RAW");

        widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary,
            "Passive IR monitoring");
    } else {
        widget_add_string_element(app->widget_main, 64, 28, AlignCenter, AlignTop, FontSecondary,
            "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 42, AlignCenter, AlignTop, FontSecondary,
            "(Low memory mode)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_ir_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventIrDetection) {
            // Refresh display on detection
            flock_bridge_scene_ir_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_magenta_10);
            consumed = true;
        }
    }

    return consumed;
}

void flock_bridge_scene_ir_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

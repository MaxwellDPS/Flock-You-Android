#include "scenes.h"

#define TAG "SceneWips"

// ============================================================================
// WIPS Scene (Wireless Intrusion Prevention System)
// ============================================================================

void flock_bridge_scene_wips_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with status indicator
    snprintf(buf, sizeof(buf), "WIPS Engine [%s]", app->wips_engine ? "ON" : "OFF");
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // Show alert count prominently
    snprintf(buf, sizeof(buf), "Security Alerts: %lu", app->wips_alert_count);
    widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

    // Show WIPS status
    if (app->wips_engine) {
        // WiFi detection capability
        snprintf(buf, sizeof(buf), "WiFi Analyzed: %lu", app->wifi_scan_count);
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary, buf);

        // Threat detection types
        widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary,
            "Evil Twin/Deauth/Karma");

        widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary,
            "Intrusion detection ON");
    } else {
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "Engine Disabled");

        widget_add_string_element(app->widget_main, 64, 40, AlignCenter, AlignTop, FontSecondary,
            "Requires ESP32 WiFi");
        widget_add_string_element(app->widget_main, 64, 52, AlignCenter, AlignTop, FontSecondary,
            "board connected");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_wips_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventWipsAlert) {
            // Refresh display and alert on WIPS detection
            flock_bridge_scene_wips_on_enter(app);
            notification_message(app->notifications, &sequence_blink_red_100);
            notification_message(app->notifications, &sequence_double_vibro);
            consumed = true;
        }
    }

    return consumed;
}

void flock_bridge_scene_wips_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

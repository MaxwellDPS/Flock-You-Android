#include "scenes.h"

#define TAG "SceneNfcScan"

// ============================================================================
// NFC Scan Scene
// ============================================================================

void flock_bridge_scene_nfc_scan_on_enter(void* context) {
    FlockBridgeApp* app = context;
    char buf[48];

    widget_reset(app->widget_main);

    // Title with status indicator
    snprintf(buf, sizeof(buf), "NFC Scanner [%s]", app->nfc_ready ? "ON" : "OFF");
    widget_add_string_element(app->widget_main, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // Show detection count prominently
    snprintf(buf, sizeof(buf), "Tags Detected: %lu", app->nfc_detection_count);
    widget_add_string_element(app->widget_main, 64, 14, AlignCenter, AlignTop, FontSecondary, buf);

    // Show scanner status
    if (app->nfc_ready) {
        widget_add_string_element(app->widget_main, 64, 26, AlignCenter, AlignTop, FontSecondary,
            "ST25R3916: Polling");

        // Show supported tag types
        widget_add_string_element(app->widget_main, 64, 38, AlignCenter, AlignTop, FontSecondary,
            "ISO14443A/B MIFARE NFC");

        widget_add_string_element(app->widget_main, 64, 50, AlignCenter, AlignTop, FontSecondary,
            "Hold tag near top edge");
    } else {
        widget_add_string_element(app->widget_main, 64, 28, AlignCenter, AlignTop, FontSecondary,
            "Scanner Disabled");
        widget_add_string_element(app->widget_main, 64, 42, AlignCenter, AlignTop, FontSecondary,
            "(Low memory mode)");
    }

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewMain);
}

bool flock_bridge_scene_nfc_scan_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventNfcDetection) {
            // Refresh display on detection
            flock_bridge_scene_nfc_scan_on_enter(app);
            notification_message(app->notifications, &sequence_blink_green_10);
            consumed = true;
        }
    }

    return consumed;
}

void flock_bridge_scene_nfc_scan_on_exit(void* context) {
    FlockBridgeApp* app = context;
    widget_reset(app->widget_main);
}

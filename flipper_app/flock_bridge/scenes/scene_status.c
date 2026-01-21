#include "scenes.h"
#include <furi_hal_power.h>

#define TAG "SceneStatus"

// Status refresh interval (500ms for smooth updates)
#define STATUS_REFRESH_INTERVAL_MS 500

// ============================================================================
// Status Scene - Real-time status display with periodic refresh
// ============================================================================

// Helper to refresh status widget content
static void flock_bridge_status_refresh(FlockBridgeApp* app) {
    widget_reset(app->widget_status);

    char buf[48];

    // === HEADER: Connection + Battery ===
    const char* link_icon = "--";
    if (app->bt_connected) link_icon = "BT";
    else if (app->usb_connected) link_icon = "USB";
    snprintf(buf, sizeof(buf), "[%s] Status  %d%%", link_icon, furi_hal_power_get_pct());
    widget_add_string_element(app->widget_status, 64, 0, AlignCenter, AlignTop, FontPrimary, buf);

    // === ROW 1: Message throughput ===
    snprintf(buf, sizeof(buf), "Msgs: TX %lu  RX %lu", app->messages_sent, app->messages_received);
    widget_add_string_element(app->widget_status, 64, 12, AlignCenter, AlignTop, FontSecondary, buf);

    // === ROW 2: Detection counts - split into two rows for readability ===
    snprintf(buf, sizeof(buf), "SubGHz:%lu  BLE:%lu",
        app->subghz_detection_count, app->ble_scan_count);
    widget_add_string_element(app->widget_status, 64, 23, AlignCenter, AlignTop, FontSecondary, buf);

    snprintf(buf, sizeof(buf), "WiFi:%lu  IR:%lu  NFC:%lu",
        app->wifi_scan_count, app->ir_detection_count, app->nfc_detection_count);
    widget_add_string_element(app->widget_status, 64, 33, AlignCenter, AlignTop, FontSecondary, buf);

    // === ROW 3: WIPS alerts (important - highlighted) ===
    if (app->wips_alert_count > 0) {
        snprintf(buf, sizeof(buf), "!! WIPS Alerts: %lu !!", app->wips_alert_count);
    } else {
        snprintf(buf, sizeof(buf), "WIPS Alerts: 0");
    }
    widget_add_string_element(app->widget_status, 64, 44, AlignCenter, AlignTop, FontSecondary, buf);

    // === ROW 4: Uptime and hardware status ===
    uint32_t uptime_sec = (furi_get_tick() - app->uptime_start) / 1000;
    uint32_t uptime_min = uptime_sec / 60;
    uint32_t uptime_hr = uptime_min / 60;
    const char* hw_status = app->external_radio_connected ? "Ext:OK" : "Ext:--";
    if (uptime_hr > 0) {
        snprintf(buf, sizeof(buf), "Up:%luh%lum  %s", uptime_hr, uptime_min % 60, hw_status);
    } else if (uptime_min > 0) {
        snprintf(buf, sizeof(buf), "Up:%lum%lus  %s", uptime_min, uptime_sec % 60, hw_status);
    } else {
        snprintf(buf, sizeof(buf), "Up:%lus  %s", uptime_sec, hw_status);
    }
    widget_add_string_element(app->widget_status, 64, 55, AlignCenter, AlignTop, FontSecondary, buf);
}

// Timer callback for status refresh
static void flock_bridge_status_timer_callback(void* context) {
    FlockBridgeApp* app = context;
    // Send custom event to trigger UI refresh on main thread
    view_dispatcher_send_custom_event(app->view_dispatcher, FlockBridgeEventRefreshStatus);
}

void flock_bridge_scene_status_on_enter(void* context) {
    FlockBridgeApp* app = context;

    // Initial status refresh
    flock_bridge_status_refresh(app);

    // Create and start the status update timer
    app->status_timer = furi_timer_alloc(flock_bridge_status_timer_callback, FuriTimerTypePeriodic, app);
    furi_timer_start(app->status_timer, furi_ms_to_ticks(STATUS_REFRESH_INTERVAL_MS));

    view_dispatcher_switch_to_view(app->view_dispatcher, FlockBridgeViewStatus);
}

bool flock_bridge_scene_status_on_event(void* context, SceneManagerEvent event) {
    FlockBridgeApp* app = context;
    bool consumed = false;

    if (event.type == SceneManagerEventTypeCustom) {
        if (event.event == FlockBridgeEventRefreshStatus) {
            // Refresh the status display
            flock_bridge_status_refresh(app);
            consumed = true;
        }
    }

    return consumed;
}

void flock_bridge_scene_status_on_exit(void* context) {
    FlockBridgeApp* app = context;

    // Stop and free the timer
    if (app->status_timer) {
        furi_timer_stop(app->status_timer);
        furi_timer_free(app->status_timer);
        app->status_timer = NULL;
    }

    widget_reset(app->widget_status);
}

#pragma once

#include "../flock_bridge_app.h"

// ============================================================================
// Scene Handler Declarations
// ============================================================================
// Each scene has three handlers: on_enter, on_event, on_exit
// These are implemented in individual scene_*.c files

// Main scene - Navigation menu
void flock_bridge_scene_main_on_enter(void* context);
bool flock_bridge_scene_main_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_main_on_exit(void* context);

// Status scene - Real-time status display
void flock_bridge_scene_status_on_enter(void* context);
bool flock_bridge_scene_status_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_status_on_exit(void* context);

// WiFi Scan scene
void flock_bridge_scene_wifi_scan_on_enter(void* context);
bool flock_bridge_scene_wifi_scan_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_wifi_scan_on_exit(void* context);

// SubGHz Scan scene
void flock_bridge_scene_subghz_scan_on_enter(void* context);
bool flock_bridge_scene_subghz_scan_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_subghz_scan_on_exit(void* context);

// BLE Scan scene
void flock_bridge_scene_ble_scan_on_enter(void* context);
bool flock_bridge_scene_ble_scan_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_ble_scan_on_exit(void* context);

// IR Scan scene
void flock_bridge_scene_ir_scan_on_enter(void* context);
bool flock_bridge_scene_ir_scan_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_ir_scan_on_exit(void* context);

// NFC Scan scene
void flock_bridge_scene_nfc_scan_on_enter(void* context);
bool flock_bridge_scene_nfc_scan_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_nfc_scan_on_exit(void* context);

// WIPS scene - Wireless Intrusion Prevention
void flock_bridge_scene_wips_on_enter(void* context);
bool flock_bridge_scene_wips_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_wips_on_exit(void* context);

// Settings scene
void flock_bridge_scene_settings_on_enter(void* context);
bool flock_bridge_scene_settings_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_settings_on_exit(void* context);

// Connection scene
void flock_bridge_scene_connection_on_enter(void* context);
bool flock_bridge_scene_connection_on_event(void* context, SceneManagerEvent event);
void flock_bridge_scene_connection_on_exit(void* context);

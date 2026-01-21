/**
 * Settings
 *
 * Handles loading, saving, and applying radio settings.
 */

#include "handlers.h"
#include "../scanners/scheduler/detection_scheduler.h"
#include <furi.h>
#include <storage/storage.h>

#define TAG "FlockSettings"

// ============================================================================
// Load Settings from Storage
// ============================================================================

bool flock_bridge_load_settings(FlockBridgeApp* app) {
    if (!app) return false;

    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    bool success = false;

    if (storage_file_open(file, FLOCK_SETTINGS_PATH, FSAM_READ, FSOM_OPEN_EXISTING)) {
        FlockSettingsFile settings_file;
        if (storage_file_read(file, &settings_file, sizeof(settings_file)) == sizeof(settings_file)) {
            if (settings_file.magic == FLOCK_SETTINGS_MAGIC &&
                settings_file.version == FLOCK_SETTINGS_VERSION) {
                memcpy(&app->radio_settings, &settings_file.settings, sizeof(FlockRadioSettings));
                success = true;
                FURI_LOG_I(TAG, "Settings loaded from storage");
            } else {
                FURI_LOG_W(TAG, "Settings file version mismatch, using defaults");
            }
        }
    } else {
        FURI_LOG_I(TAG, "No settings file found, using defaults");
    }

    storage_file_close(file);
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);

    return success;
}

// ============================================================================
// Save Settings to Storage
// ============================================================================

bool flock_bridge_save_settings(FlockBridgeApp* app) {
    if (!app) return false;

    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    bool success = false;

    if (storage_file_open(file, FLOCK_SETTINGS_PATH, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        FlockSettingsFile settings_file = {
            .magic = FLOCK_SETTINGS_MAGIC,
            .version = FLOCK_SETTINGS_VERSION,
        };
        memcpy(&settings_file.settings, &app->radio_settings, sizeof(FlockRadioSettings));

        if (storage_file_write(file, &settings_file, sizeof(settings_file)) == sizeof(settings_file)) {
            success = true;
            FURI_LOG_I(TAG, "Settings saved to storage");
        } else {
            FURI_LOG_E(TAG, "Failed to write settings file");
        }
    } else {
        FURI_LOG_E(TAG, "Failed to open settings file for writing");
    }

    storage_file_close(file);
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);

    return success;
}

// ============================================================================
// Apply Radio Settings to Detection Scheduler
// ============================================================================

void flock_bridge_apply_radio_settings(FlockBridgeApp* app) {
    if (!app || !app->detection_scheduler) return;

    // Convert FlockRadioSourceMode to RadioSourceMode
    RadioSourceSettings sources = {
        .subghz_source = (RadioSourceMode)app->radio_settings.subghz_source,
        .ble_source = (RadioSourceMode)app->radio_settings.ble_source,
        .wifi_source = (RadioSourceMode)app->radio_settings.wifi_source,
    };

    detection_scheduler_set_radio_sources(app->detection_scheduler, &sources);

    FURI_LOG_I(TAG, "Radio settings applied: SubGHz=%s, BLE=%s, WiFi=%s",
        flock_bridge_get_source_name(app->radio_settings.subghz_source),
        flock_bridge_get_source_name(app->radio_settings.ble_source),
        flock_bridge_get_source_name(app->radio_settings.wifi_source));
}

// ============================================================================
// Get Radio Source Name
// ============================================================================

const char* flock_bridge_get_source_name(FlockRadioSourceMode mode) {
    switch (mode) {
    case FlockRadioSourceAuto: return "Auto";
    case FlockRadioSourceInternal: return "Internal";
    case FlockRadioSourceExternal: return "External";
    case FlockRadioSourceBoth: return "Both";
    default: return "Unknown";
    }
}

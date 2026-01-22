/**
 * Handlers Master Header
 *
 * Includes all handler declarations for the Flock Bridge application.
 */

#pragma once

#include "../flock_bridge_app.h"
#include "../protocol/flock_protocol.h"

// Include active probe handlers (organized by radio type)
#include "probes/probes.h"

// ============================================================================
// Detection Callbacks - Send detections to connected device
// ============================================================================

// Note: FlockSubGhzDetection, FlockBleDevice, FlockIrDetection, FlockNfcDetection
// are defined in protocol/flock_protocol.h which is included above.

void on_subghz_detection(const FlockSubGhzDetection* detection, void* context);
void on_ble_detection(const FlockBleDevice* device, void* context);
void on_wifi_detection(const FlockWifiNetwork* network, void* context);
void on_wifi_deauth(const uint8_t* bssid, const uint8_t* target, uint8_t reason, uint32_t count, void* context);
void on_ir_detection(const FlockIrDetection* detection, void* context);
void on_nfc_detection(const FlockNfcDetection* detection, void* context);

// ============================================================================
// Message Handler - Processes incoming protocol messages
// ============================================================================

/**
 * Data received callback - handles incoming serial data
 */
void flock_bridge_data_received(void* context, uint8_t* data, size_t length);

// ============================================================================
// Settings - Persistence and configuration
// ============================================================================

// Settings file path and constants
#define FLOCK_SETTINGS_PATH APP_DATA_PATH("settings.bin")
#define FLOCK_SETTINGS_MAGIC 0x464C4F43  // "FLOC"
#define FLOCK_SETTINGS_VERSION 1

// Settings file structure
typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint32_t version;
    FlockRadioSettings settings;
} FlockSettingsFile;

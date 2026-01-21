#pragma once

#include "../flock_bridge_app.h"
#include "../protocol/flock_protocol.h"

// ============================================================================
// Detection Callbacks - Send detections to connected device
// ============================================================================

// Note: FlockSubGhzDetection, FlockBleDevice, FlockIrDetection, FlockNfcDetection
// are defined in protocol/flock_protocol.h which is included above.

void on_subghz_detection(const FlockSubGhzDetection* detection, void* context);
void on_ble_detection(const FlockBleDevice* device, void* context);
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
// Active Probes - Hardware test/emulation functions
// ============================================================================

/**
 * Handle LF probe (125kHz TPMS wake) command
 */
void handle_lf_probe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle IR strobe (traffic preemption) command
 */
void handle_ir_strobe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle WiFi probe request command
 */
void handle_wifi_probe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle BLE active scan command
 */
void handle_ble_active_scan(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle Zigbee beacon command
 */
void handle_zigbee_beacon_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle GPIO pulse (inductive loop) command
 */
void handle_gpio_pulse_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle Sub-GHz replay command
 */
void handle_subghz_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle Wiegand replay command
 */
void handle_wiegand_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle MagSpoof (magnetic stripe emulation) command
 */
void handle_magspoof_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle iButton emulation command
 */
void handle_ibutton_emulate(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

/**
 * Handle NRF24 keystroke injection command
 */
void handle_nrf24_inject_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

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

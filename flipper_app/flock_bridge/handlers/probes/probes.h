/**
 * Active Probes - Master Header
 *
 * Includes all probe handler declarations organized by radio type.
 */

#pragma once

#include "../../flock_bridge_app.h"
#include "../../protocol/flock_protocol.h"

// ============================================================================
// Sub-GHz Probes (315/433/868/915 MHz)
// ============================================================================

void handle_subghz_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// LF Probes (125 kHz)
// ============================================================================

void handle_lf_probe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// IR Probes (Infrared)
// ============================================================================

void handle_ir_strobe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// WiFi Probes (2.4/5 GHz)
// ============================================================================

void handle_wifi_probe_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// BLE Probes (2.4 GHz Bluetooth Low Energy)
// ============================================================================

void handle_ble_active_scan(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// Zigbee Probes (2.4 GHz / Sub-GHz fallback)
// ============================================================================

void handle_zigbee_beacon_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// NRF24 Probes (2.4 GHz Nordic)
// ============================================================================

void handle_nrf24_inject_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// GPIO Probes (Digital I/O)
// ============================================================================

void handle_gpio_pulse_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

// ============================================================================
// Access Control Probes (Wiegand, Magstripe, iButton)
// ============================================================================

void handle_wiegand_replay_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);
void handle_magspoof_tx(FlockBridgeApp* app, const uint8_t* buffer, size_t length);
void handle_ibutton_emulate(FlockBridgeApp* app, const uint8_t* buffer, size_t length);

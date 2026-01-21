#pragma once

/**
 * wifi_scanner_internal.h - Internal definitions for WiFi scanner module
 *
 * This header is NOT part of the public API. It is used to share internal
 * definitions between wifi_scanner.c and wifi_deauth_detect.c.
 */

#include "wifi_scanner.h"
#include <string.h>
#include <stdlib.h>

// ============================================================================
// Internal Constants
// ============================================================================

#define WIFI_SCANNER_TAG "WifiScanner"

#define MAX_NETWORKS 64
#define MAX_PROBES 32
#define NETWORK_TIMEOUT_MS 30000  // Remove networks not seen in 30 seconds

// ============================================================================
// External Radio Protocol Commands (ESP32)
// ============================================================================

// Commands sent to ESP32
#define ExtRadioCmdWifiScanStart    0x20
#define ExtRadioCmdWifiScanStop     0x21
#define ExtRadioCmdWifiSetChannel   0x22

// Responses from ESP32
#define ExtRadioRespWifiNetwork     0x30
#define ExtRadioRespWifiProbe       0x31
#define ExtRadioRespWifiDeauth      0x32
#define ExtRadioRespWifiScanDone    0x33

// ============================================================================
// External Radio Protocol Structures
// ============================================================================

typedef struct {
    char ssid[33];
    uint8_t bssid[6];
    int8_t rssi;
    uint8_t channel;
    uint8_t security;
    uint8_t hidden;
    uint16_t frame_count;
} ExtWifiNetwork;

typedef struct {
    uint8_t sta_mac[6];
    char ssid[33];
    int8_t rssi;
    uint8_t channel;
} ExtWifiProbe;

typedef struct {
    uint8_t bssid[6];
    uint8_t target_mac[6];
    uint8_t reason;
    int8_t rssi;
    uint32_t count;
} ExtWifiDeauth;

// ============================================================================
// Scanner Structure (Internal)
// ============================================================================

struct WifiScanner {
    WifiScannerConfig config;
    WifiScannerStats stats;

    // External radio
    ExternalRadioManager* radio;

    // State
    bool running;
    uint8_t current_channel;

    // Network list
    WifiNetworkExtended networks[MAX_NETWORKS];
    uint8_t network_count;

    // Recent probes (circular buffer)
    WifiProbeRequest probes[MAX_PROBES];
    uint8_t probe_head;

    // Thread and sync
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool should_stop;
};

// ============================================================================
// Internal Functions - Frame Handlers (wifi_deauth_detect.c)
// ============================================================================

/**
 * Handle a WiFi network frame from ESP32.
 * Called with scanner mutex held.
 */
void wifi_scanner_handle_network(
    WifiScanner* scanner,
    const uint8_t* data,
    size_t len);

/**
 * Handle a probe request frame from ESP32.
 * Called with scanner mutex held.
 */
void wifi_scanner_handle_probe(
    WifiScanner* scanner,
    const uint8_t* data,
    size_t len);

/**
 * Handle a deauth frame from ESP32.
 * Called with scanner mutex held.
 */
void wifi_scanner_handle_deauth(
    WifiScanner* scanner,
    const uint8_t* data,
    size_t len);

/**
 * Handle scan complete notification from ESP32.
 * Called with scanner mutex held.
 */
void wifi_scanner_handle_scan_done(WifiScanner* scanner);

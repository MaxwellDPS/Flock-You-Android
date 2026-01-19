#pragma once

#include "../protocol/flock_protocol.h"
#include "../helpers/external_radio.h"
#include <furi.h>
#include <furi_hal.h>

// ============================================================================
// WiFi Scanner (via External ESP32)
// ============================================================================
// Handles WiFi scanning through an external ESP32 module connected via UART.
// The Flipper Zero doesn't have built-in WiFi, so this requires external hardware.
//
// Supported external boards:
// - ESP32-WROOM/WROVER with custom firmware
// - ESP8266 with custom firmware (limited features)
// - Marauder board (compatible)
//
// Features:
// - Network discovery (SSID, BSSID, channel, security)
// - Probe request monitoring
// - Deauth detection (for WIPS)
// - Hidden network detection
// - Channel hopping

typedef struct WifiScanner WifiScanner;

// ============================================================================
// WiFi Scan Modes
// ============================================================================

typedef enum {
    WifiScanModePassive = 0,    // Listen only (more stealthy)
    WifiScanModeActive,          // Send probe requests (faster)
    WifiScanModeMonitor,         // Full monitor mode (raw frames)
} WifiScanMode;

// ============================================================================
// WiFi Security Detection
// ============================================================================

typedef enum {
    WifiSecTypeOpen = 0,
    WifiSecTypeWEP,
    WifiSecTypeWPA,
    WifiSecTypeWPA2,
    WifiSecTypeWPA3,
    WifiSecTypeWPA2Enterprise,
    WifiSecTypeWPA3Enterprise,
    WifiSecTypeUnknown = 0xFF,
} WifiSecurityType;

// ============================================================================
// Extended Network Info
// ============================================================================

typedef struct {
    FlockWifiNetwork base;
    uint32_t first_seen;
    uint32_t last_seen;
    uint16_t beacon_count;
    uint16_t probe_response_count;
    uint8_t client_count;
    bool is_hidden;
    bool has_pmf;               // Protected Management Frames (WPA3)
} WifiNetworkExtended;

// ============================================================================
// Probe Request Info
// ============================================================================

typedef struct {
    uint8_t sta_mac[6];         // Station MAC
    char target_ssid[33];       // SSID being probed for
    int8_t rssi;
    uint8_t channel;
    uint32_t timestamp;
} WifiProbeRequest;

// ============================================================================
// Deauth Frame Info (for WIPS)
// ============================================================================

typedef struct {
    uint8_t bssid[6];           // AP BSSID
    uint8_t target_mac[6];      // Target station (or broadcast)
    uint8_t reason_code;
    int8_t rssi;
    uint32_t count;             // Number detected in window
    uint32_t first_seen;
    uint32_t last_seen;
} WifiDeauthDetection;

// ============================================================================
// Callbacks
// ============================================================================

typedef void (*WifiNetworkCallback)(
    const WifiNetworkExtended* network,
    void* context);

typedef void (*WifiProbeCallback)(
    const WifiProbeRequest* probe,
    void* context);

typedef void (*WifiDeauthCallback)(
    const WifiDeauthDetection* deauth,
    void* context);

typedef void (*WifiScanCompleteCallback)(
    uint8_t network_count,
    void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    WifiScanMode scan_mode;
    bool detect_hidden;         // Try to detect hidden networks
    bool monitor_probes;        // Monitor probe requests
    bool detect_deauths;        // Detect deauth attacks (for WIPS)
    uint8_t channel;            // 0 = channel hop, 1-14 = fixed channel
    uint32_t dwell_time_ms;     // Time per channel when hopping (default: 100ms)
    int8_t rssi_threshold;      // Minimum RSSI to report (default: -90 dBm)

    WifiNetworkCallback network_callback;
    WifiProbeCallback probe_callback;
    WifiDeauthCallback deauth_callback;
    WifiScanCompleteCallback complete_callback;
    void* callback_context;
} WifiScannerConfig;

// ============================================================================
// Statistics
// ============================================================================

typedef struct {
    uint32_t scans_completed;
    uint32_t networks_found;
    uint32_t unique_networks;
    uint32_t hidden_networks;
    uint32_t probes_captured;
    uint32_t deauths_detected;
    uint32_t channels_scanned;
} WifiScannerStats;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate WiFi scanner.
 * Requires an external radio manager to be configured and connected.
 */
WifiScanner* wifi_scanner_alloc(ExternalRadioManager* radio_manager);

/**
 * Free WiFi scanner.
 */
void wifi_scanner_free(WifiScanner* scanner);

/**
 * Configure the scanner.
 */
void wifi_scanner_configure(
    WifiScanner* scanner,
    const WifiScannerConfig* config);

/**
 * Check if ESP32/external radio is available.
 */
bool wifi_scanner_is_available(WifiScanner* scanner);

/**
 * Start WiFi scanning.
 */
bool wifi_scanner_start(WifiScanner* scanner);

/**
 * Stop WiFi scanning.
 */
void wifi_scanner_stop(WifiScanner* scanner);

/**
 * Check if scanner is running.
 */
bool wifi_scanner_is_running(WifiScanner* scanner);

/**
 * Set scan channel (0 = channel hop).
 */
void wifi_scanner_set_channel(WifiScanner* scanner, uint8_t channel);

/**
 * Get current channel.
 */
uint8_t wifi_scanner_get_channel(WifiScanner* scanner);

/**
 * Get scanner statistics.
 */
void wifi_scanner_get_stats(WifiScanner* scanner, WifiScannerStats* stats);

/**
 * Get network count from last scan.
 */
uint8_t wifi_scanner_get_network_count(WifiScanner* scanner);

/**
 * Get network by index (0 to count-1).
 */
bool wifi_scanner_get_network(
    WifiScanner* scanner,
    uint8_t index,
    WifiNetworkExtended* network);

/**
 * Clear network list.
 */
void wifi_scanner_clear_networks(WifiScanner* scanner);

/**
 * Get security type name.
 */
const char* wifi_scanner_get_security_name(WifiSecurityType type);

/**
 * Parse security type from ESP32 response.
 */
WifiSecurityType wifi_scanner_parse_security(uint8_t auth_mode);

#pragma once

#include "../protocol/flock_protocol.h"
#include <furi.h>

// ============================================================================
// WIPS (Wireless Intrusion Prevention System) Engine
// ============================================================================

/**
 * WIPS Engine for detecting WiFi-based attacks and anomalies.
 * Analyzes WiFi scan results to detect:
 * - Evil Twin attacks (same SSID, different BSSID)
 * - Deauthentication flood attacks
 * - Karma attacks (responding to all probe requests)
 * - Rogue access points
 * - Hidden networks with strong signals
 * - Weak encryption (WEP)
 * - Suspicious open networks
 */

typedef struct FlockWipsEngine FlockWipsEngine;

// Callback for WIPS alerts
typedef void (*WipsAlertCallback)(const FlockWipsAlert* alert, void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    bool detect_evil_twin;
    bool detect_deauth;
    bool detect_karma;
    bool detect_rogue_ap;
    bool detect_hidden_strong;
    bool detect_weak_encryption;
    bool detect_suspicious_open;

    int8_t hidden_strong_rssi_threshold;  // Default: -55 dBm
    uint32_t deauth_detection_window_ms;  // Default: 5000 ms
    uint8_t deauth_threshold_count;       // Default: 10 deauths

    WipsAlertCallback alert_callback;
    void* callback_context;
} WipsConfig;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate a new WIPS engine with default configuration.
 */
FlockWipsEngine* flock_wips_engine_alloc(void);

/**
 * Free the WIPS engine.
 */
void flock_wips_engine_free(FlockWipsEngine* engine);

/**
 * Configure the WIPS engine.
 */
void flock_wips_engine_configure(FlockWipsEngine* engine, const WipsConfig* config);

/**
 * Set the alert callback.
 */
void flock_wips_engine_set_callback(
    FlockWipsEngine* engine,
    WipsAlertCallback callback,
    void* context);

/**
 * Analyze a WiFi scan result for threats.
 * Returns the number of alerts generated.
 */
uint8_t flock_wips_engine_analyze(
    FlockWipsEngine* engine,
    const FlockWifiScanResult* scan_result);

/**
 * Record a deauthentication frame (for deauth attack detection).
 * Call this when a deauth frame is observed.
 */
void flock_wips_engine_record_deauth(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const uint8_t* client_mac);

/**
 * Record a probe response (for Karma attack detection).
 */
void flock_wips_engine_record_probe_response(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const char* ssid);

/**
 * Clear all learned network state.
 */
void flock_wips_engine_reset(FlockWipsEngine* engine);

/**
 * Get statistics about WIPS detections.
 */
typedef struct {
    uint32_t evil_twin_count;
    uint32_t deauth_count;
    uint32_t karma_count;
    uint32_t rogue_ap_count;
    uint32_t total_alerts;
} WipsStats;

void flock_wips_engine_get_stats(FlockWipsEngine* engine, WipsStats* stats);

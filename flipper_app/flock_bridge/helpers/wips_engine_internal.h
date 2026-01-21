#pragma once

#include "wips_engine.h"
#include <string.h>
#include <stdlib.h>

// ============================================================================
// Internal Constants
// ============================================================================

#define TAG "FlockWips"

#define MAX_KNOWN_NETWORKS 64
#define MAX_DEAUTH_RECORDS 32
#define MAX_PROBE_RESPONSES 32

// ============================================================================
// Internal Structures
// ============================================================================

typedef struct {
    char ssid[33];
    uint8_t bssid[6];
    int8_t rssi;
    uint32_t last_seen;
    bool valid;
} KnownNetwork;

typedef struct {
    uint8_t bssid[6];
    uint8_t client_mac[6];
    uint32_t timestamp;
    bool valid;
} DeauthRecord;

typedef struct {
    uint8_t bssid[6];
    char ssid[33];
    uint32_t timestamp;
    bool valid;
} ProbeResponseRecord;

struct FlockWipsEngine {
    WipsConfig config;
    WipsStats stats;

    KnownNetwork known_networks[MAX_KNOWN_NETWORKS];
    uint8_t known_network_count;

    DeauthRecord deauth_records[MAX_DEAUTH_RECORDS];
    uint8_t deauth_record_head;

    ProbeResponseRecord probe_responses[MAX_PROBE_RESPONSES];
    uint8_t probe_response_head;

    FuriMutex* mutex;
};

// ============================================================================
// Internal Helper Functions
// ============================================================================

/**
 * Compare two MAC addresses for equality.
 */
static inline bool mac_equals(const uint8_t* mac1, const uint8_t* mac2) {
    return memcmp(mac1, mac2, 6) == 0;
}

/**
 * Emit a WIPS alert through the configured callback.
 */
void wips_emit_alert(
    FlockWipsEngine* engine,
    WipsAlertType type,
    WipsSeverity severity,
    const char* ssid,
    const uint8_t bssids[][6],
    uint8_t bssid_count,
    const char* description);

// ============================================================================
// Detector Functions (implemented in wips_detectors.c)
// ============================================================================

/**
 * Check if an SSID matches suspicious open network patterns.
 */
bool wips_is_suspicious_open_ssid(const char* ssid);

/**
 * Detect evil twin attacks (same SSID, different BSSID).
 * Returns 1 if alert was generated, 0 otherwise.
 */
uint8_t wips_detect_evil_twin(
    FlockWipsEngine* engine,
    const FlockWifiScanResult* scan_result,
    uint8_t network_index,
    uint8_t safe_network_count);

/**
 * Detect weak encryption (WEP).
 * Returns 1 if alert was generated, 0 otherwise.
 */
uint8_t wips_detect_weak_encryption(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network);

/**
 * Detect suspicious open networks (honeypots).
 * Returns 1 if alert was generated, 0 otherwise.
 */
uint8_t wips_detect_suspicious_open(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network);

/**
 * Detect strong hidden networks.
 * Returns 1 if alert was generated, 0 otherwise.
 */
uint8_t wips_detect_hidden_strong(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network);

/**
 * Check for deauth attack based on recorded frames.
 * Called after recording a new deauth frame.
 */
void wips_check_deauth_attack(
    FlockWipsEngine* engine,
    const uint8_t* bssid);

/**
 * Check for karma attack based on probe responses.
 * Called after recording a new probe response.
 */
void wips_check_karma_attack(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const char* ssid);

/**
 * Update known networks database with a new observation.
 */
void wips_update_known_networks(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network,
    uint32_t timestamp);

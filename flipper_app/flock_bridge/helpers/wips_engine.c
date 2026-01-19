#include "wips_engine.h"
#include <string.h>
#include <stdlib.h>

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
// Helper Functions
// ============================================================================

static bool mac_equals(const uint8_t* mac1, const uint8_t* mac2) {
    return memcmp(mac1, mac2, 6) == 0;
}

static bool is_suspicious_open_ssid(const char* ssid) {
    const char* suspicious_patterns[] = {
        "free", "FREE", "Free",
        "public", "PUBLIC", "Public",
        "guest", "GUEST", "Guest",
        "wifi", "WiFi", "WIFI",
        "open", "OPEN", "Open",
        "hotspot", "Hotspot", "HOTSPOT",
        "starbucks", "Starbucks",
        "mcdonalds", "McDonald",
        "airport", "Airport",
        "hotel", "Hotel",
        NULL
    };

    for (int i = 0; suspicious_patterns[i] != NULL; i++) {
        if (strstr(ssid, suspicious_patterns[i]) != NULL) {
            return true;
        }
    }
    return false;
}

static void emit_alert(
    FlockWipsEngine* engine,
    WipsAlertType type,
    WipsSeverity severity,
    const char* ssid,
    const uint8_t bssids[][6],
    uint8_t bssid_count,
    const char* description) {

    FlockWipsAlert alert = {0};
    alert.timestamp = furi_get_tick() / 1000; // Seconds since boot
    alert.alert_type = type;
    alert.severity = severity;

    if (ssid) {
        strncpy(alert.ssid, ssid, sizeof(alert.ssid) - 1);
    }

    alert.bssid_count = bssid_count > 4 ? 4 : bssid_count;
    for (uint8_t i = 0; i < alert.bssid_count; i++) {
        memcpy(alert.bssids[i], bssids[i], 6);
    }

    if (description) {
        strncpy(alert.description, description, sizeof(alert.description) - 1);
    }

    engine->stats.total_alerts++;

    if (engine->config.alert_callback) {
        engine->config.alert_callback(&alert, engine->config.callback_context);
    }
}

// ============================================================================
// API Implementation
// ============================================================================

FlockWipsEngine* flock_wips_engine_alloc(void) {
    FlockWipsEngine* engine = malloc(sizeof(FlockWipsEngine));
    if (!engine) return NULL;

    memset(engine, 0, sizeof(FlockWipsEngine));

    // Default configuration
    engine->config.detect_evil_twin = true;
    engine->config.detect_deauth = true;
    engine->config.detect_karma = true;
    engine->config.detect_rogue_ap = true;
    engine->config.detect_hidden_strong = true;
    engine->config.detect_weak_encryption = true;
    engine->config.detect_suspicious_open = true;
    engine->config.hidden_strong_rssi_threshold = -55;
    engine->config.deauth_detection_window_ms = 5000;
    engine->config.deauth_threshold_count = 10;

    engine->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    return engine;
}

void flock_wips_engine_free(FlockWipsEngine* engine) {
    if (!engine) return;

    if (engine->mutex) {
        furi_mutex_free(engine->mutex);
    }

    free(engine);
}

void flock_wips_engine_configure(FlockWipsEngine* engine, const WipsConfig* config) {
    if (!engine || !config) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);
    memcpy(&engine->config, config, sizeof(WipsConfig));
    furi_mutex_release(engine->mutex);
}

void flock_wips_engine_set_callback(
    FlockWipsEngine* engine,
    WipsAlertCallback callback,
    void* context) {

    if (!engine) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);
    engine->config.alert_callback = callback;
    engine->config.callback_context = context;
    furi_mutex_release(engine->mutex);
}

uint8_t flock_wips_engine_analyze(
    FlockWipsEngine* engine,
    const FlockWifiScanResult* scan_result) {

    if (!engine || !scan_result) return 0;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);

    uint8_t alert_count = 0;
    char desc[64];

    // Group networks by SSID for evil twin detection
    // Clamp network_count to prevent buffer overflows
    uint8_t safe_network_count = scan_result->network_count;
    if (safe_network_count > MAX_WIFI_NETWORKS) {
        safe_network_count = MAX_WIFI_NETWORKS;
    }
    for (uint8_t i = 0; i < safe_network_count; i++) {
        const FlockWifiNetwork* net = &scan_result->networks[i];

        // Skip if SSID is empty
        if (net->ssid[0] == '\0') {
            // Check for strong hidden network
            if (engine->config.detect_hidden_strong &&
                net->rssi > engine->config.hidden_strong_rssi_threshold) {

                uint8_t bssid_array[1][6];
                memcpy(bssid_array[0], net->bssid, 6);

                snprintf(desc, sizeof(desc), "Strong hidden network (%d dBm)", net->rssi);
                emit_alert(engine, WipsAlertHiddenNetworkStrong, WipsSeverityMedium,
                    "[Hidden]", bssid_array, 1, desc);
                alert_count++;
            }
            continue;
        }

        // Evil Twin Detection: Check for same SSID, different BSSID
        if (engine->config.detect_evil_twin) {
            uint8_t matching_bssids[4][6];
            uint8_t matching_count = 0;

            // Add the current network's BSSID first
            memcpy(matching_bssids[matching_count++], net->bssid, 6);

            for (uint8_t j = i + 1; j < safe_network_count && matching_count < 4; j++) {
                if (strcmp(net->ssid, scan_result->networks[j].ssid) == 0 &&
                    !mac_equals(net->bssid, scan_result->networks[j].bssid)) {
                    memcpy(matching_bssids[matching_count++], scan_result->networks[j].bssid, 6);
                }
            }

            if (matching_count > 1) {
                snprintf(desc, sizeof(desc), "Multiple APs (%d) with same SSID", matching_count);
                emit_alert(engine, WipsAlertEvilTwin, WipsSeverityHigh,
                    net->ssid, matching_bssids, matching_count, desc);
                engine->stats.evil_twin_count++;
                alert_count++;
            }
        }

        // Weak Encryption Detection (WEP)
        if (engine->config.detect_weak_encryption && net->security == WifiSecurityWEP) {
            uint8_t bssid_array[1][6];
            memcpy(bssid_array[0], net->bssid, 6);

            snprintf(desc, sizeof(desc), "Using deprecated WEP encryption");
            emit_alert(engine, WipsAlertWeakEncryption, WipsSeverityLow,
                net->ssid, bssid_array, 1, desc);
            alert_count++;
        }

        // Suspicious Open Network Detection
        if (engine->config.detect_suspicious_open &&
            net->security == WifiSecurityOpen &&
            is_suspicious_open_ssid(net->ssid)) {

            uint8_t bssid_array[1][6];
            memcpy(bssid_array[0], net->bssid, 6);

            snprintf(desc, sizeof(desc), "Suspicious open network - possible honeypot");
            emit_alert(engine, WipsAlertSuspiciousOpenNetwork, WipsSeverityMedium,
                net->ssid, bssid_array, 1, desc);
            alert_count++;
        }

        // Update known networks for future analysis
        bool found = false;
        for (uint8_t k = 0; k < engine->known_network_count; k++) {
            if (mac_equals(engine->known_networks[k].bssid, net->bssid)) {
                engine->known_networks[k].rssi = net->rssi;
                engine->known_networks[k].last_seen = scan_result->timestamp;
                found = true;
                break;
            }
        }

        if (!found && engine->known_network_count < MAX_KNOWN_NETWORKS) {
            KnownNetwork* kn = &engine->known_networks[engine->known_network_count++];
            strncpy(kn->ssid, net->ssid, sizeof(kn->ssid) - 1);
            memcpy(kn->bssid, net->bssid, 6);
            kn->rssi = net->rssi;
            kn->last_seen = scan_result->timestamp;
            kn->valid = true;
        }
    }

    furi_mutex_release(engine->mutex);
    return alert_count;
}

void flock_wips_engine_record_deauth(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const uint8_t* client_mac) {

    if (!engine || !bssid) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);

    // Add to circular buffer
    DeauthRecord* record = &engine->deauth_records[engine->deauth_record_head];
    memcpy(record->bssid, bssid, 6);
    if (client_mac) {
        memcpy(record->client_mac, client_mac, 6);
    }
    record->timestamp = furi_get_tick();
    record->valid = true;

    engine->deauth_record_head = (engine->deauth_record_head + 1) % MAX_DEAUTH_RECORDS;

    // Check for deauth attack
    if (engine->config.detect_deauth) {
        uint32_t now = furi_get_tick();
        uint32_t window = engine->config.deauth_detection_window_ms;
        uint8_t count = 0;

        for (uint8_t i = 0; i < MAX_DEAUTH_RECORDS; i++) {
            if (engine->deauth_records[i].valid &&
                (now - engine->deauth_records[i].timestamp) < window) {
                count++;
            }
        }

        if (count >= engine->config.deauth_threshold_count) {
            uint8_t bssid_array[1][6];
            memcpy(bssid_array[0], bssid, 6);

            char desc[64];
            snprintf(desc, sizeof(desc), "Deauth flood: %d frames in %dms", count, (int)window);
            emit_alert(engine, WipsAlertDeauthAttack, WipsSeverityCritical,
                NULL, bssid_array, 1, desc);
            engine->stats.deauth_count++;

            // Clear records after alert
            for (uint8_t i = 0; i < MAX_DEAUTH_RECORDS; i++) {
                engine->deauth_records[i].valid = false;
            }
        }
    }

    furi_mutex_release(engine->mutex);
}

void flock_wips_engine_record_probe_response(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const char* ssid) {

    if (!engine || !bssid || !ssid) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);

    // Check if this BSSID is responding to many different SSIDs (Karma attack)
    if (engine->config.detect_karma) {
        uint8_t different_ssids = 0;

        for (uint8_t i = 0; i < MAX_PROBE_RESPONSES; i++) {
            if (engine->probe_responses[i].valid &&
                mac_equals(engine->probe_responses[i].bssid, bssid) &&
                strcmp(engine->probe_responses[i].ssid, ssid) != 0) {
                different_ssids++;
            }
        }

        if (different_ssids >= 3) {
            uint8_t bssid_array[1][6];
            memcpy(bssid_array[0], bssid, 6);

            char desc[64];
            snprintf(desc, sizeof(desc), "AP responding to %d+ different probe requests", different_ssids);
            emit_alert(engine, WipsAlertKarmaAttack, WipsSeverityHigh,
                ssid, bssid_array, 1, desc);
            engine->stats.karma_count++;
        }
    }

    // Store probe response in circular buffer
    ProbeResponseRecord* record = &engine->probe_responses[engine->probe_response_head];
    memcpy(record->bssid, bssid, 6);
    strncpy(record->ssid, ssid, sizeof(record->ssid) - 1);
    record->timestamp = furi_get_tick();
    record->valid = true;

    engine->probe_response_head = (engine->probe_response_head + 1) % MAX_PROBE_RESPONSES;

    furi_mutex_release(engine->mutex);
}

void flock_wips_engine_reset(FlockWipsEngine* engine) {
    if (!engine) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);

    engine->known_network_count = 0;
    engine->deauth_record_head = 0;
    engine->probe_response_head = 0;

    memset(engine->known_networks, 0, sizeof(engine->known_networks));
    memset(engine->deauth_records, 0, sizeof(engine->deauth_records));
    memset(engine->probe_responses, 0, sizeof(engine->probe_responses));
    memset(&engine->stats, 0, sizeof(engine->stats));

    furi_mutex_release(engine->mutex);
}

void flock_wips_engine_get_stats(FlockWipsEngine* engine, WipsStats* stats) {
    if (!engine || !stats) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);
    memcpy(stats, &engine->stats, sizeof(WipsStats));
    furi_mutex_release(engine->mutex);
}

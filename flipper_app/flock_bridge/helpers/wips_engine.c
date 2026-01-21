#include "wips_engine_internal.h"
#include <stdio.h>

// ============================================================================
// Alert Emission
// ============================================================================

void wips_emit_alert(
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
// Engine Lifecycle
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

// ============================================================================
// Configuration
// ============================================================================

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

// ============================================================================
// Analysis Entry Point
// ============================================================================

uint8_t flock_wips_engine_analyze(
    FlockWipsEngine* engine,
    const FlockWifiScanResult* scan_result) {

    if (!engine || !scan_result) return 0;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);

    uint8_t alert_count = 0;

    // Clamp network_count to prevent buffer overflows
    uint8_t safe_network_count = scan_result->network_count;
    if (safe_network_count > MAX_WIFI_NETWORKS) {
        safe_network_count = MAX_WIFI_NETWORKS;
    }

    for (uint8_t i = 0; i < safe_network_count; i++) {
        const FlockWifiNetwork* net = &scan_result->networks[i];

        // Handle hidden networks (empty SSID)
        if (net->ssid[0] == '\0') {
            alert_count += wips_detect_hidden_strong(engine, net);
            continue;
        }

        // Run detection algorithms
        alert_count += wips_detect_evil_twin(engine, scan_result, i, safe_network_count);
        alert_count += wips_detect_weak_encryption(engine, net);
        alert_count += wips_detect_suspicious_open(engine, net);

        // Update known networks database
        wips_update_known_networks(engine, net, scan_result->timestamp);
    }

    furi_mutex_release(engine->mutex);
    return alert_count;
}

// ============================================================================
// Frame Recording
// ============================================================================

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
    } else {
        memset(record->client_mac, 0, 6);
    }
    record->timestamp = furi_get_tick();
    record->valid = true;

    engine->deauth_record_head = (engine->deauth_record_head + 1) % MAX_DEAUTH_RECORDS;

    // Check for deauth attack
    wips_check_deauth_attack(engine, bssid);

    furi_mutex_release(engine->mutex);
}

void flock_wips_engine_record_probe_response(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const char* ssid) {

    if (!engine || !bssid || !ssid) return;

    furi_mutex_acquire(engine->mutex, FuriWaitForever);

    // Check for karma attack before recording
    wips_check_karma_attack(engine, bssid, ssid);

    // Store probe response in circular buffer
    ProbeResponseRecord* record = &engine->probe_responses[engine->probe_response_head];
    memcpy(record->bssid, bssid, 6);
    strncpy(record->ssid, ssid, sizeof(record->ssid) - 1);
    record->ssid[sizeof(record->ssid) - 1] = '\0';
    record->timestamp = furi_get_tick();
    record->valid = true;

    engine->probe_response_head = (engine->probe_response_head + 1) % MAX_PROBE_RESPONSES;

    furi_mutex_release(engine->mutex);
}

// ============================================================================
// State Management
// ============================================================================

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

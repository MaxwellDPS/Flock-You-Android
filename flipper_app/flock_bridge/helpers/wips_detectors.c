#include "wips_engine_internal.h"
#include <stdio.h>

// Pattern matching for suspicious SSIDs
bool wips_is_suspicious_open_ssid(const char* ssid) {
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

// Evil Twin Detection: same SSID, different BSSID
uint8_t wips_detect_evil_twin(
    FlockWipsEngine* engine,
    const FlockWifiScanResult* scan_result,
    uint8_t network_index,
    uint8_t safe_network_count) {

    if (!engine->config.detect_evil_twin) {
        return 0;
    }

    const FlockWifiNetwork* net = &scan_result->networks[network_index];
    uint8_t matching_bssids[4][6];
    uint8_t matching_count = 0;
    char desc[64];

    // Add the current network's BSSID first
    memcpy(matching_bssids[matching_count++], net->bssid, 6);

    // Look for other networks with same SSID but different BSSID
    for (uint8_t j = network_index + 1; j < safe_network_count && matching_count < 4; j++) {
        if (strcmp(net->ssid, scan_result->networks[j].ssid) == 0 &&
            !mac_equals(net->bssid, scan_result->networks[j].bssid)) {
            memcpy(matching_bssids[matching_count++], scan_result->networks[j].bssid, 6);
        }
    }

    if (matching_count > 1) {
        snprintf(desc, sizeof(desc), "Multiple APs (%d) with same SSID", matching_count);
        wips_emit_alert(engine, WipsAlertEvilTwin, WipsSeverityHigh,
            net->ssid, matching_bssids, matching_count, desc);
        engine->stats.evil_twin_count++;
        return 1;
    }

    return 0;
}

// Weak Encryption Detection (WEP)
uint8_t wips_detect_weak_encryption(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network) {

    if (!engine->config.detect_weak_encryption) {
        return 0;
    }

    if (network->security != WifiSecurityWEP) {
        return 0;
    }

    uint8_t bssid_array[1][6];
    memcpy(bssid_array[0], network->bssid, 6);

    char desc[64];
    snprintf(desc, sizeof(desc), "Using deprecated WEP encryption");
    wips_emit_alert(engine, WipsAlertWeakEncryption, WipsSeverityLow,
        network->ssid, bssid_array, 1, desc);

    return 1;
}

// Suspicious Open Network Detection (honeypots)
uint8_t wips_detect_suspicious_open(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network) {

    if (!engine->config.detect_suspicious_open) {
        return 0;
    }

    if (network->security != WifiSecurityOpen) {
        return 0;
    }

    if (!wips_is_suspicious_open_ssid(network->ssid)) {
        return 0;
    }

    uint8_t bssid_array[1][6];
    memcpy(bssid_array[0], network->bssid, 6);

    char desc[64];
    snprintf(desc, sizeof(desc), "Suspicious open network - possible honeypot");
    wips_emit_alert(engine, WipsAlertSuspiciousOpenNetwork, WipsSeverityMedium,
        network->ssid, bssid_array, 1, desc);

    return 1;
}

// Hidden Network Detection (strong signal)
uint8_t wips_detect_hidden_strong(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network) {

    if (!engine->config.detect_hidden_strong) {
        return 0;
    }

    // Only check networks with empty SSID (hidden)
    if (network->ssid[0] != '\0') {
        return 0;
    }

    if (network->rssi <= engine->config.hidden_strong_rssi_threshold) {
        return 0;
    }

    uint8_t bssid_array[1][6];
    memcpy(bssid_array[0], network->bssid, 6);

    char desc[64];
    snprintf(desc, sizeof(desc), "Strong hidden network (%d dBm)", network->rssi);
    wips_emit_alert(engine, WipsAlertHiddenNetworkStrong, WipsSeverityMedium,
        "[Hidden]", bssid_array, 1, desc);

    return 1;
}

// Deauth Attack Detection (flood detection)
void wips_check_deauth_attack(
    FlockWipsEngine* engine,
    const uint8_t* bssid) {

    if (!engine->config.detect_deauth) {
        return;
    }

    uint32_t now = furi_get_tick();
    uint32_t window = engine->config.deauth_detection_window_ms;
    uint8_t count = 0;

    // Count recent deauth frames within the detection window
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
        wips_emit_alert(engine, WipsAlertDeauthAttack, WipsSeverityCritical,
            NULL, bssid_array, 1, desc);
        engine->stats.deauth_count++;

        // Clear records after alert to prevent duplicate alerts
        for (uint8_t i = 0; i < MAX_DEAUTH_RECORDS; i++) {
            engine->deauth_records[i].valid = false;
        }
    }
}

// Karma Attack Detection (responding to all probes)
void wips_check_karma_attack(
    FlockWipsEngine* engine,
    const uint8_t* bssid,
    const char* ssid) {

    if (!engine->config.detect_karma) {
        return;
    }

    uint8_t different_ssids = 0;

    // Count how many different SSIDs this BSSID has responded to
    for (uint8_t i = 0; i < MAX_PROBE_RESPONSES; i++) {
        if (engine->probe_responses[i].valid &&
            mac_equals(engine->probe_responses[i].bssid, bssid) &&
            strcmp(engine->probe_responses[i].ssid, ssid) != 0) {
            different_ssids++;
        }
    }

    // Threshold: AP responding to 3+ different SSIDs indicates Karma attack
    if (different_ssids >= 3) {
        uint8_t bssid_array[1][6];
        memcpy(bssid_array[0], bssid, 6);

        char desc[64];
        snprintf(desc, sizeof(desc), "AP responding to %d+ different probe requests", different_ssids);
        wips_emit_alert(engine, WipsAlertKarmaAttack, WipsSeverityHigh,
            ssid, bssid_array, 1, desc);
        engine->stats.karma_count++;
    }
}

// Known Networks Management
void wips_update_known_networks(
    FlockWipsEngine* engine,
    const FlockWifiNetwork* network,
    uint32_t timestamp) {

    // Search for existing entry
    for (uint8_t k = 0; k < engine->known_network_count; k++) {
        if (mac_equals(engine->known_networks[k].bssid, network->bssid)) {
            // Update existing entry
            engine->known_networks[k].rssi = network->rssi;
            engine->known_networks[k].last_seen = timestamp;
            return;
        }
    }

    // Add new entry if space available
    if (engine->known_network_count < MAX_KNOWN_NETWORKS) {
        KnownNetwork* kn = &engine->known_networks[engine->known_network_count++];
        strncpy(kn->ssid, network->ssid, sizeof(kn->ssid) - 1);
        kn->ssid[sizeof(kn->ssid) - 1] = '\0';
        memcpy(kn->bssid, network->bssid, 6);
        kn->rssi = network->rssi;
        kn->last_seen = timestamp;
        kn->valid = true;
    }
}

/**
 * wifi_deauth_detect.c - WiFi frame detection and analysis
 *
 * This module handles parsing and processing of WiFi frames received from
 * the ESP32 external radio. It includes:
 * - Network beacon/probe response handling
 * - Probe request monitoring
 * - Deauth attack detection (for WIPS)
 *
 * This file is part of the wifi_scanner module. The public API remains
 * in wifi_scanner.h - this file contains internal implementation only.
 */

#include "wifi_scanner_internal.h"

#define TAG WIFI_SCANNER_TAG

// ============================================================================
// Network Frame Handler
// ============================================================================

void wifi_scanner_handle_network(
    WifiScanner* scanner,
    const uint8_t* data,
    size_t len) {

    if (!scanner || !data) return;

    // Parse network from ESP32
    if (len < sizeof(ExtWifiNetwork)) {
        FURI_LOG_W(TAG, "Network frame too short: %zu bytes", len);
        return;
    }

    const ExtWifiNetwork* ext_net = (const ExtWifiNetwork*)data;

    // Check if we already have this network (by BSSID)
    int existing_idx = -1;
    for (int i = 0; i < scanner->network_count; i++) {
        if (memcmp(scanner->networks[i].base.bssid, ext_net->bssid, 6) == 0) {
            existing_idx = i;
            break;
        }
    }

    uint32_t now = furi_get_tick();

    if (existing_idx >= 0) {
        // Update existing network
        WifiNetworkExtended* net = &scanner->networks[existing_idx];
        net->base.rssi = ext_net->rssi;
        net->base.channel = ext_net->channel;
        net->last_seen = now;
        net->beacon_count += ext_net->frame_count;
    } else if (scanner->network_count < MAX_NETWORKS) {
        // Add new network
        WifiNetworkExtended* net = &scanner->networks[scanner->network_count];
        memset(net, 0, sizeof(WifiNetworkExtended));

        strncpy(net->base.ssid, ext_net->ssid, 32);
        net->base.ssid[32] = '\0';
        memcpy(net->base.bssid, ext_net->bssid, 6);
        net->base.rssi = ext_net->rssi;
        net->base.channel = ext_net->channel;
        net->base.security = ext_net->security;
        net->base.hidden = ext_net->hidden;

        net->first_seen = now;
        net->last_seen = now;
        net->beacon_count = ext_net->frame_count;
        net->is_hidden = ext_net->hidden;

        scanner->network_count++;
        scanner->stats.networks_found++;

        if (ext_net->hidden) {
            scanner->stats.hidden_networks++;
        }

        // Callback for new network
        if (scanner->config.network_callback) {
            scanner->config.network_callback(net, scanner->config.callback_context);
        }

        FURI_LOG_I(TAG, "WiFi: %s (%d dBm, ch %d, sec %d)",
            net->base.ssid[0] ? net->base.ssid : "<hidden>",
            net->base.rssi, net->base.channel, net->base.security);
    } else {
        FURI_LOG_W(TAG, "Network list full, ignoring new network");
    }
}

// ============================================================================
// Probe Request Handler
// ============================================================================

void wifi_scanner_handle_probe(
    WifiScanner* scanner,
    const uint8_t* data,
    size_t len) {

    if (!scanner || !data) return;

    // Parse probe request from ESP32
    if (len < sizeof(ExtWifiProbe)) {
        FURI_LOG_W(TAG, "Probe frame too short: %zu bytes", len);
        return;
    }

    const ExtWifiProbe* ext_probe = (const ExtWifiProbe*)data;

    // Store in circular buffer
    WifiProbeRequest* probe = &scanner->probes[scanner->probe_head];
    memcpy(probe->sta_mac, ext_probe->sta_mac, 6);
    strncpy(probe->target_ssid, ext_probe->ssid, 32);
    probe->target_ssid[32] = '\0';
    probe->rssi = ext_probe->rssi;
    probe->channel = ext_probe->channel;
    probe->timestamp = furi_get_tick();

    scanner->probe_head = (scanner->probe_head + 1) % MAX_PROBES;
    scanner->stats.probes_captured++;

    // Callback
    if (scanner->config.probe_callback) {
        scanner->config.probe_callback(probe, scanner->config.callback_context);
    }

    FURI_LOG_D(TAG, "Probe: %02X:%02X:%02X -> %s",
        probe->sta_mac[3], probe->sta_mac[4], probe->sta_mac[5],
        probe->target_ssid[0] ? probe->target_ssid : "<broadcast>");
}

// ============================================================================
// Deauth Frame Handler (WIPS Detection)
// ============================================================================
//
// Deauthentication attacks are a common WiFi attack vector where an attacker
// sends forged deauth frames to disconnect clients from an access point.
// This is often used as a precursor to:
// - Evil twin attacks
// - Handshake capture for offline cracking
// - Denial of service
//
// Detection heuristics:
// - High rate of deauth frames from same source
// - Deauth frames not matching legitimate AP behavior
// - Broadcast deauth frames (affect all clients)
//
// ============================================================================

void wifi_scanner_handle_deauth(
    WifiScanner* scanner,
    const uint8_t* data,
    size_t len) {

    if (!scanner || !data) return;

    // Parse deauth frame from ESP32
    if (len < sizeof(ExtWifiDeauth)) {
        FURI_LOG_W(TAG, "Deauth frame too short: %zu bytes", len);
        return;
    }

    const ExtWifiDeauth* ext_deauth = (const ExtWifiDeauth*)data;

    // Build detection event
    WifiDeauthDetection deauth;
    memcpy(deauth.bssid, ext_deauth->bssid, 6);
    memcpy(deauth.target_mac, ext_deauth->target_mac, 6);
    deauth.reason_code = ext_deauth->reason;
    deauth.rssi = ext_deauth->rssi;
    deauth.count = ext_deauth->count;
    deauth.first_seen = furi_get_tick();
    deauth.last_seen = deauth.first_seen;

    scanner->stats.deauths_detected++;

    // Check if this is a broadcast deauth (more suspicious)
    bool is_broadcast = (deauth.target_mac[0] == 0xFF &&
                         deauth.target_mac[1] == 0xFF &&
                         deauth.target_mac[2] == 0xFF &&
                         deauth.target_mac[3] == 0xFF &&
                         deauth.target_mac[4] == 0xFF &&
                         deauth.target_mac[5] == 0xFF);

    // Log with appropriate severity
    if (is_broadcast || deauth.count > 10) {
        FURI_LOG_E(TAG, "ATTACK: Deauth flood! BSSID: %02X:%02X:%02X:%02X:%02X:%02X, "
            "count: %lu, broadcast: %s",
            deauth.bssid[0], deauth.bssid[1], deauth.bssid[2],
            deauth.bssid[3], deauth.bssid[4], deauth.bssid[5],
            deauth.count, is_broadcast ? "YES" : "NO");
    } else {
        FURI_LOG_W(TAG, "Deauth detected! BSSID: %02X:%02X:%02X, count: %lu",
            deauth.bssid[3], deauth.bssid[4], deauth.bssid[5], deauth.count);
    }

    // Callback - this is important for WIPS
    if (scanner->config.deauth_callback) {
        scanner->config.deauth_callback(&deauth, scanner->config.callback_context);
    }
}

// ============================================================================
// Scan Complete Handler
// ============================================================================

void wifi_scanner_handle_scan_done(WifiScanner* scanner) {
    if (!scanner) return;

    scanner->stats.scans_completed++;
    scanner->stats.channels_scanned++;

    // Update unique network count
    scanner->stats.unique_networks = scanner->network_count;

    if (scanner->config.complete_callback) {
        scanner->config.complete_callback(
            scanner->network_count,
            scanner->config.callback_context);
    }

    FURI_LOG_I(TAG, "Scan complete: %d networks", scanner->network_count);
}

// ============================================================================
// Security Type Utilities
// ============================================================================

const char* wifi_scanner_get_security_name(WifiSecurityType type) {
    switch (type) {
    case WifiSecurityOpen:           return "Open";
    case WifiSecurityWEP:            return "WEP";
    case WifiSecurityWPA:            return "WPA";
    case WifiSecurityWPA2:           return "WPA2";
    case WifiSecurityWPA3:           return "WPA3";
    case WifiSecurityWPA2Enterprise: return "WPA2-Enterprise";
    case WifiSecurityWPA3Enterprise: return "WPA3-Enterprise";
    default:                         return "Unknown";
    }
}

WifiSecurityType wifi_scanner_parse_security(uint8_t auth_mode) {
    // ESP32 wifi_auth_mode_t values
    switch (auth_mode) {
    case 0: return WifiSecurityOpen;
    case 1: return WifiSecurityWEP;
    case 2: return WifiSecurityWPA;
    case 3: return WifiSecurityWPA2;
    case 4: return WifiSecurityWPA2Enterprise;
    case 5: return WifiSecurityWPA3;
    case 6: return WifiSecurityWPA2;  // WPA2_WPA3_PSK mapped to WPA2
    case 7: return WifiSecurityWPA3Enterprise;
    default: return WifiSecurityUnknown;
    }
}

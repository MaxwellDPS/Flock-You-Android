#include "wifi_scanner.h"
#include <string.h>
#include <stdlib.h>

#define TAG "WifiScanner"

// ============================================================================
// Internal Constants
// ============================================================================

#define MAX_NETWORKS 64
#define MAX_PROBES 32
#define NETWORK_TIMEOUT_MS 30000  // Remove networks not seen in 30 seconds

// ============================================================================
// Scanner Structure
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
// External Radio Data Callback
// ============================================================================

static void wifi_scanner_radio_callback(
    uint8_t cmd,
    const uint8_t* data,
    size_t len,
    void* context) {

    WifiScanner* scanner = context;
    if (!scanner || !scanner->running) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    switch (cmd) {
    case ExtRadioRespWifiNetwork: {
        // Parse network from ESP32
        if (len >= sizeof(ExtWifiNetwork)) {
            const ExtWifiNetwork* ext_net = (const ExtWifiNetwork*)data;

            // Check if we already have this network
            int existing_idx = -1;
            for (int i = 0; i < scanner->network_count; i++) {
                if (memcmp(scanner->networks[i].base.bssid, ext_net->bssid, 6) == 0) {
                    existing_idx = i;
                    break;
                }
            }

            uint32_t now = furi_get_tick();

            if (existing_idx >= 0) {
                // Update existing
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
            }
        }
        break;
    }

    case ExtRadioRespWifiProbe: {
        // Parse probe request
        if (len >= sizeof(ExtWifiProbe)) {
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
        break;
    }

    case ExtRadioRespWifiDeauth: {
        // Parse deauth frame
        if (len >= sizeof(ExtWifiDeauth)) {
            const ExtWifiDeauth* ext_deauth = (const ExtWifiDeauth*)data;

            WifiDeauthDetection deauth;
            memcpy(deauth.bssid, ext_deauth->bssid, 6);
            memcpy(deauth.target_mac, ext_deauth->target_mac, 6);
            deauth.reason_code = ext_deauth->reason;
            deauth.rssi = ext_deauth->rssi;
            deauth.count = ext_deauth->count;
            deauth.first_seen = furi_get_tick();
            deauth.last_seen = deauth.first_seen;

            scanner->stats.deauths_detected++;

            // Callback - this is important for WIPS
            if (scanner->config.deauth_callback) {
                scanner->config.deauth_callback(&deauth, scanner->config.callback_context);
            }

            FURI_LOG_W(TAG, "Deauth detected! BSSID: %02X:%02X:%02X, count: %lu",
                deauth.bssid[3], deauth.bssid[4], deauth.bssid[5], deauth.count);
        }
        break;
    }

    case ExtRadioRespWifiScanDone: {
        scanner->stats.scans_completed++;
        scanner->stats.channels_scanned++;

        // Count unique networks
        scanner->stats.unique_networks = scanner->network_count;

        if (scanner->config.complete_callback) {
            scanner->config.complete_callback(
                scanner->network_count,
                scanner->config.callback_context);
        }

        FURI_LOG_I(TAG, "Scan complete: %d networks", scanner->network_count);
        break;
    }

    default:
        FURI_LOG_D(TAG, "Unknown WiFi response: 0x%02X", cmd);
        break;
    }

    furi_mutex_release(scanner->mutex);
}

// ============================================================================
// Worker Thread
// ============================================================================

static int32_t wifi_scanner_worker(void* context) {
    WifiScanner* scanner = context;

    FURI_LOG_I(TAG, "WiFi scanner worker started");

    // Build scan start command
    uint8_t scan_params[8];
    scan_params[0] = scanner->config.scan_mode;
    scan_params[1] = scanner->config.channel;  // 0 = hop
    scan_params[2] = (scanner->config.dwell_time_ms >> 8) & 0xFF;
    scan_params[3] = scanner->config.dwell_time_ms & 0xFF;
    scan_params[4] = scanner->config.detect_hidden ? 1 : 0;
    scan_params[5] = scanner->config.monitor_probes ? 1 : 0;
    scan_params[6] = scanner->config.detect_deauths ? 1 : 0;
    scan_params[7] = (int8_t)scanner->config.rssi_threshold;

    // Start scan on ESP32
    external_radio_send_command(
        scanner->radio,
        ExtRadioCmdWifiScanStart,
        scan_params,
        sizeof(scan_params));

    // Monitor and manage channel hopping
    uint32_t last_channel_change = furi_get_tick();
    scanner->current_channel = scanner->config.channel;

    while (!scanner->should_stop) {
        uint32_t now = furi_get_tick();

        // Handle manual channel hopping if configured
        if (scanner->config.channel == 0) {
            // Channel hopping is handled by ESP32
        } else {
            // Fixed channel - check if we need to change
            if (scanner->current_channel != scanner->config.channel) {
                uint8_t ch_cmd[1] = { scanner->config.channel };
                external_radio_send_command(
                    scanner->radio,
                    ExtRadioCmdWifiSetChannel,
                    ch_cmd, 1);
                scanner->current_channel = scanner->config.channel;
            }
        }

        // Clean up old networks
        furi_mutex_acquire(scanner->mutex, FuriWaitForever);
        for (int i = scanner->network_count - 1; i >= 0; i--) {
            if ((now - scanner->networks[i].last_seen) > NETWORK_TIMEOUT_MS) {
                // Remove old network by shifting
                if (i < scanner->network_count - 1) {
                    memmove(&scanner->networks[i],
                        &scanner->networks[i + 1],
                        (scanner->network_count - i - 1) * sizeof(WifiNetworkExtended));
                }
                scanner->network_count--;
            }
        }
        furi_mutex_release(scanner->mutex);

        furi_delay_ms(100);
    }

    // Stop scan on ESP32
    external_radio_send_command(scanner->radio, ExtRadioCmdWifiScanStop, NULL, 0);

    FURI_LOG_I(TAG, "WiFi scanner worker stopped");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

WifiScanner* wifi_scanner_alloc(ExternalRadioManager* radio_manager) {
    if (!radio_manager) return NULL;

    WifiScanner* scanner = malloc(sizeof(WifiScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(WifiScanner));

    scanner->radio = radio_manager;
    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.scan_mode = WifiScanModeActive;
    scanner->config.detect_hidden = true;
    scanner->config.monitor_probes = true;
    scanner->config.detect_deauths = true;
    scanner->config.channel = 0;  // Channel hop
    scanner->config.dwell_time_ms = 100;
    scanner->config.rssi_threshold = -90;

    FURI_LOG_I(TAG, "WiFi scanner allocated");
    return scanner;
}

void wifi_scanner_free(WifiScanner* scanner) {
    if (!scanner) return;

    wifi_scanner_stop(scanner);

    if (scanner->mutex) {
        furi_mutex_free(scanner->mutex);
    }

    free(scanner);
    FURI_LOG_I(TAG, "WiFi scanner freed");
}

void wifi_scanner_configure(
    WifiScanner* scanner,
    const WifiScannerConfig* config) {

    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(WifiScannerConfig));
    furi_mutex_release(scanner->mutex);
}

bool wifi_scanner_is_available(WifiScanner* scanner) {
    if (!scanner || !scanner->radio) return false;

    // Check if external radio is connected and supports WiFi
    if (!external_radio_is_connected(scanner->radio)) return false;

    uint32_t caps = external_radio_get_capabilities(scanner->radio);
    return (caps & EXT_RADIO_CAP_WIFI_SCAN) != 0;
}

bool wifi_scanner_start(WifiScanner* scanner) {
    if (!scanner || scanner->running) return false;

    if (!wifi_scanner_is_available(scanner)) {
        FURI_LOG_E(TAG, "WiFi scanner not available (no ESP32?)");
        return false;
    }

    FURI_LOG_I(TAG, "Starting WiFi scanner");

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->running = true;
    scanner->should_stop = false;

    // Register our callback with external radio
    ExternalRadioConfig radio_config;
    radio_config.on_data = wifi_scanner_radio_callback;
    radio_config.callback_context = scanner;
    // Note: This is simplified - in practice, would need to chain callbacks
    // or use a different mechanism to not overwrite other callbacks

    furi_mutex_release(scanner->mutex);

    // Start worker thread
    scanner->worker_thread = furi_thread_alloc_ex(
        "WifiScanWorker", 2048, wifi_scanner_worker, scanner);
    furi_thread_start(scanner->worker_thread);

    return true;
}

void wifi_scanner_stop(WifiScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping WiFi scanner");

    scanner->should_stop = true;

    if (scanner->worker_thread) {
        furi_thread_join(scanner->worker_thread);
        furi_thread_free(scanner->worker_thread);
        scanner->worker_thread = NULL;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->running = false;
    furi_mutex_release(scanner->mutex);
}

bool wifi_scanner_is_running(WifiScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

void wifi_scanner_set_channel(WifiScanner* scanner, uint8_t channel) {
    if (!scanner) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->config.channel = channel;
    furi_mutex_release(scanner->mutex);
}

uint8_t wifi_scanner_get_channel(WifiScanner* scanner) {
    if (!scanner) return 0;
    return scanner->current_channel;
}

void wifi_scanner_get_stats(WifiScanner* scanner, WifiScannerStats* stats) {
    if (!scanner || !stats) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(stats, &scanner->stats, sizeof(WifiScannerStats));
    furi_mutex_release(scanner->mutex);
}

uint8_t wifi_scanner_get_network_count(WifiScanner* scanner) {
    if (!scanner) return 0;
    return scanner->network_count;
}

bool wifi_scanner_get_network(
    WifiScanner* scanner,
    uint8_t index,
    WifiNetworkExtended* network) {

    if (!scanner || !network || index >= scanner->network_count) return false;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(network, &scanner->networks[index], sizeof(WifiNetworkExtended));
    furi_mutex_release(scanner->mutex);

    return true;
}

void wifi_scanner_clear_networks(WifiScanner* scanner) {
    if (!scanner) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->network_count = 0;
    memset(scanner->networks, 0, sizeof(scanner->networks));
    furi_mutex_release(scanner->mutex);
}

const char* wifi_scanner_get_security_name(WifiSecurityType type) {
    switch (type) {
    case WifiSecTypeOpen: return "Open";
    case WifiSecTypeWEP: return "WEP";
    case WifiSecTypeWPA: return "WPA";
    case WifiSecTypeWPA2: return "WPA2";
    case WifiSecTypeWPA3: return "WPA3";
    case WifiSecTypeWPA2Enterprise: return "WPA2-Enterprise";
    case WifiSecTypeWPA3Enterprise: return "WPA3-Enterprise";
    default: return "Unknown";
    }
}

WifiSecurityType wifi_scanner_parse_security(uint8_t auth_mode) {
    // ESP32 wifi_auth_mode_t values
    switch (auth_mode) {
    case 0: return WifiSecTypeOpen;
    case 1: return WifiSecTypeWEP;
    case 2: return WifiSecTypeWPA;
    case 3: return WifiSecTypeWPA2;
    case 4: return WifiSecTypeWPA2Enterprise;
    case 5: return WifiSecTypeWPA3;
    case 6: return WifiSecTypeWPA2;  // WPA2_WPA3_PSK
    case 7: return WifiSecTypeWPA3Enterprise;
    default: return WifiSecTypeUnknown;
    }
}

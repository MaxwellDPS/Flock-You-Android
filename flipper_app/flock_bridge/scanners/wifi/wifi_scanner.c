/**
 * wifi_scanner.c - Core WiFi scanner lifecycle and ESP32 communication
 *
 * This module provides the public API for WiFi scanning through an external
 * ESP32 module. Frame parsing and detection logic is in wifi_deauth_detect.c.
 *
 * The public API in wifi_scanner.h remains unchanged.
 */

#include "wifi_scanner_internal.h"

#define TAG WIFI_SCANNER_TAG

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
    case ExtRadioRespWifiNetwork:
        wifi_scanner_handle_network(scanner, data, len);
        break;

    case ExtRadioRespWifiProbe:
        wifi_scanner_handle_probe(scanner, data, len);
        break;

    case ExtRadioRespWifiDeauth:
        wifi_scanner_handle_deauth(scanner, data, len);
        break;

    case ExtRadioRespWifiScanDone:
        wifi_scanner_handle_scan_done(scanner);
        break;

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

    // Initialize channel tracking
    UNUSED(furi_get_tick()); // Reserved for future channel hopping implementation
    scanner->current_channel = scanner->config.channel;

    while (!scanner->should_stop) {
        uint32_t now = furi_get_tick();

        // Handle manual channel change if configured for fixed channel
        if (scanner->config.channel != 0) {
            if (scanner->current_channel != scanner->config.channel) {
                uint8_t ch_cmd[1] = { scanner->config.channel };
                external_radio_send_command(
                    scanner->radio,
                    ExtRadioCmdWifiSetChannel,
                    ch_cmd, 1);
                scanner->current_channel = scanner->config.channel;
            }
        }

        // Clean up old networks (not seen recently)
        furi_mutex_acquire(scanner->mutex, FuriWaitForever);
        for (int i = scanner->network_count - 1; i >= 0; i--) {
            if ((now - scanner->networks[i].last_seen) > NETWORK_TIMEOUT_MS) {
                // Remove old network by shifting array
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
// Public API - Lifecycle
// ============================================================================

WifiScanner* wifi_scanner_alloc(ExternalRadioManager* radio_manager) {
    if (!radio_manager) return NULL;

    WifiScanner* scanner = malloc(sizeof(WifiScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(WifiScanner));

    scanner->radio = radio_manager;
    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default configuration
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

// ============================================================================
// Public API - Scanner Control
// ============================================================================

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

    // Register callback with external radio
    ExternalRadioConfig radio_config;
    radio_config.on_data = wifi_scanner_radio_callback;
    radio_config.callback_context = scanner;
    UNUSED(radio_config); // Callback registration handled elsewhere

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

// ============================================================================
// Public API - Channel Control
// ============================================================================

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

// ============================================================================
// Public API - Statistics and Network Access
// ============================================================================

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

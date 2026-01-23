/**
 * Flock WiFi Scanner - ESP32 Firmware
 *
 * This firmware runs on an ESP32 connected to a Flipper Zero via UART.
 * It provides WiFi scanning capabilities that the Flipper Zero lacks.
 *
 * Connections:
 *   ESP32 TX (GPIO 17) -> Flipper RX (Pin 14)
 *   ESP32 RX (GPIO 16) -> Flipper TX (Pin 13)
 *   ESP32 GND -> Flipper GND
 *   ESP32 3.3V -> Flipper 3.3V (or external power)
 *
 * Protocol: Binary protocol matching external_radio.h
 *   [0xAA][LEN_H][LEN_L][CMD][PAYLOAD...][CRC8]
 */

#include <WiFi.h>
#include <esp_wifi.h>

// ============================================================================
// Configuration
// ============================================================================

#define SERIAL_BAUD 115200
#define FLIPPER_SERIAL Serial2  // GPIO 16/17
#define DEBUG_SERIAL Serial     // USB for debugging

#define FIRMWARE_VERSION_MAJOR 1
#define FIRMWARE_VERSION_MINOR 0
#define FIRMWARE_VERSION_PATCH 0

// Packet protocol
#define START_BYTE 0xAA
#define MAX_PAYLOAD 512

// Commands (Flipper -> ESP32)
enum Command {
    CMD_PING = 0x01,
    CMD_GET_INFO = 0x02,
    CMD_RESET = 0x03,

    CMD_WIFI_SCAN_START = 0x10,
    CMD_WIFI_SCAN_STOP = 0x11,
    CMD_WIFI_SET_CHANNEL = 0x12,
    CMD_WIFI_SET_MODE = 0x13,
    CMD_WIFI_DEAUTH = 0x14,
    CMD_WIFI_PROBE = 0x15,
};

// Responses (ESP32 -> Flipper)
enum Response {
    RESP_ACK = 0x01,
    RESP_NACK = 0x02,
    RESP_INFO = 0x03,

    RESP_WIFI_NETWORK = 0x10,
    RESP_WIFI_SCAN_DONE = 0x11,
    RESP_WIFI_PROBE = 0x12,
    RESP_WIFI_DEAUTH = 0x13,
    RESP_WIFI_RAW = 0x14,
};

// Radio types
enum RadioType {
    RADIO_NONE = 0,
    RADIO_ESP32 = 1,
    RADIO_ESP8266 = 2,
};

// Capability flags
#define CAP_WIFI_SCAN      (1 << 0)
#define CAP_WIFI_MONITOR   (1 << 1)
#define CAP_WIFI_DEAUTH    (1 << 2)
#define CAP_WIFI_INJECT    (1 << 3)

// ============================================================================
// Data Structures
// ============================================================================

// Matches ExtRadioInfo in external_radio.h
struct __attribute__((packed)) RadioInfo {
    uint8_t type;
    uint8_t version_major;
    uint8_t version_minor;
    uint8_t version_patch;
    char name[16];
    uint32_t capabilities;
};

// Matches ExtWifiNetwork in external_radio.h
struct __attribute__((packed)) WifiNetwork {
    char ssid[33];
    uint8_t bssid[6];
    int8_t rssi;
    uint8_t channel;
    uint8_t security;
    uint8_t hidden;
    uint16_t frame_count;
};

// Matches ExtWifiProbe in external_radio.h
struct __attribute__((packed)) WifiProbe {
    uint8_t sta_mac[6];
    char ssid[33];
    int8_t rssi;
    uint8_t channel;
    uint32_t timestamp;
};

// Matches ExtWifiDeauth in external_radio.h
struct __attribute__((packed)) WifiDeauth {
    uint8_t bssid[6];
    uint8_t target_mac[6];
    uint8_t reason;
    int8_t rssi;
    uint32_t count;
};

// Scan parameters from Flipper
struct ScanParams {
    uint8_t mode;           // 0=passive, 1=active, 2=monitor
    uint8_t channel;        // 0=hop, 1-14=fixed
    uint16_t dwell_time_ms;
    bool detect_hidden;
    bool monitor_probes;
    bool detect_deauths;
    int8_t rssi_threshold;
};

// ============================================================================
// Global State
// ============================================================================

bool scanning = false;
bool monitor_mode = false;
ScanParams scan_params;
uint8_t current_channel = 1;

// Packet receive state machine
enum RxState {
    RX_WAIT_START,
    RX_LEN_HIGH,
    RX_LEN_LOW,
    RX_CMD,
    RX_PAYLOAD,
    RX_CRC
};

RxState rx_state = RX_WAIT_START;
uint16_t rx_payload_len = 0;
uint16_t rx_payload_idx = 0;
uint8_t rx_cmd = 0;
uint8_t rx_buffer[MAX_PAYLOAD];
uint8_t rx_crc = 0;

// ============================================================================
// Packet Helpers
// ============================================================================

uint8_t calc_crc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
    }
    return crc;
}

void send_packet(uint8_t cmd, const uint8_t* payload, size_t payload_len) {
    uint8_t header[4];
    header[0] = START_BYTE;
    header[1] = (payload_len >> 8) & 0xFF;
    header[2] = payload_len & 0xFF;
    header[3] = cmd;

    uint8_t crc = header[1] ^ header[2] ^ header[3];
    for (size_t i = 0; i < payload_len; i++) {
        crc ^= payload[i];
    }

    FLIPPER_SERIAL.write(header, 4);
    if (payload && payload_len > 0) {
        FLIPPER_SERIAL.write(payload, payload_len);
    }
    FLIPPER_SERIAL.write(crc);
}

void send_ack() {
    send_packet(RESP_ACK, nullptr, 0);
}

void send_nack() {
    send_packet(RESP_NACK, nullptr, 0);
}

void send_info() {
    RadioInfo info;
    memset(&info, 0, sizeof(info));
    info.type = RADIO_ESP32;
    info.version_major = FIRMWARE_VERSION_MAJOR;
    info.version_minor = FIRMWARE_VERSION_MINOR;
    info.version_patch = FIRMWARE_VERSION_PATCH;
    strncpy(info.name, "FlockESP32", sizeof(info.name) - 1);
    info.capabilities = CAP_WIFI_SCAN | CAP_WIFI_MONITOR;

    send_packet(RESP_INFO, (uint8_t*)&info, sizeof(info));
}

// ============================================================================
// WiFi Scanning
// ============================================================================

uint8_t convert_auth_mode(wifi_auth_mode_t auth) {
    switch (auth) {
        case WIFI_AUTH_OPEN: return 0;
        case WIFI_AUTH_WEP: return 1;
        case WIFI_AUTH_WPA_PSK: return 2;
        case WIFI_AUTH_WPA2_PSK: return 3;
        case WIFI_AUTH_WPA_WPA2_PSK: return 3;
        case WIFI_AUTH_WPA2_ENTERPRISE: return 4;
        case WIFI_AUTH_WPA3_PSK: return 5;
        case WIFI_AUTH_WPA2_WPA3_PSK: return 6;
        default: return 255;
    }
}

void do_wifi_scan() {
    DEBUG_SERIAL.println("Starting WiFi scan...");

    int n = WiFi.scanNetworks(false, scan_params.detect_hidden);

    DEBUG_SERIAL.printf("Found %d networks\n", n);

    for (int i = 0; i < n; i++) {
        // Filter by RSSI threshold
        if (WiFi.RSSI(i) < scan_params.rssi_threshold) {
            continue;
        }

        WifiNetwork network;
        memset(&network, 0, sizeof(network));

        // Copy SSID
        String ssid = WiFi.SSID(i);
        strncpy(network.ssid, ssid.c_str(), 32);
        network.ssid[32] = '\0';

        // Copy BSSID
        uint8_t* bssid = WiFi.BSSID(i);
        memcpy(network.bssid, bssid, 6);

        // Fill in details
        network.rssi = WiFi.RSSI(i);
        network.channel = WiFi.channel(i);
        network.security = convert_auth_mode(WiFi.encryptionType(i));
        network.hidden = (ssid.length() == 0) ? 1 : 0;
        network.frame_count = 1;

        // Send to Flipper
        send_packet(RESP_WIFI_NETWORK, (uint8_t*)&network, sizeof(network));

        DEBUG_SERIAL.printf("  %s (%d dBm, ch %d)\n",
            network.ssid[0] ? network.ssid : "<hidden>",
            network.rssi, network.channel);
    }

    // Send scan complete
    send_packet(RESP_WIFI_SCAN_DONE, nullptr, 0);

    DEBUG_SERIAL.println("Scan complete");
}

void start_scanning() {
    if (scanning) return;

    scanning = true;

    // Set WiFi mode to station for basic scanning
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();

    DEBUG_SERIAL.printf("Scanning started (mode=%d, ch=%d, hidden=%d)\n",
        scan_params.mode, scan_params.channel, scan_params.detect_hidden);
}

void stop_scanning() {
    scanning = false;
    DEBUG_SERIAL.println("Scanning stopped");
}

// ============================================================================
// Command Handlers
// ============================================================================

void handle_command(uint8_t cmd, const uint8_t* payload, size_t len) {
    DEBUG_SERIAL.printf("Received cmd: 0x%02X, len: %d\n", cmd, len);

    switch (cmd) {
        case CMD_PING:
            send_ack();
            break;

        case CMD_GET_INFO:
            send_info();
            break;

        case CMD_RESET:
            send_ack();
            ESP.restart();
            break;

        case CMD_WIFI_SCAN_START:
            if (len >= 8) {
                scan_params.mode = payload[0];
                scan_params.channel = payload[1];
                scan_params.dwell_time_ms = (payload[2] << 8) | payload[3];
                scan_params.detect_hidden = payload[4] != 0;
                scan_params.monitor_probes = payload[5] != 0;
                scan_params.detect_deauths = payload[6] != 0;
                scan_params.rssi_threshold = (int8_t)payload[7];
            } else {
                // Default parameters
                scan_params.mode = 1;  // Active
                scan_params.channel = 0;
                scan_params.dwell_time_ms = 100;
                scan_params.detect_hidden = true;
                scan_params.monitor_probes = false;
                scan_params.detect_deauths = false;
                scan_params.rssi_threshold = -90;
            }
            start_scanning();
            send_ack();
            break;

        case CMD_WIFI_SCAN_STOP:
            stop_scanning();
            send_ack();
            break;

        case CMD_WIFI_SET_CHANNEL:
            if (len >= 1) {
                current_channel = payload[0];
                if (current_channel >= 1 && current_channel <= 14) {
                    esp_wifi_set_channel(current_channel, WIFI_SECOND_CHAN_NONE);
                    DEBUG_SERIAL.printf("Channel set to %d\n", current_channel);
                }
            }
            send_ack();
            break;

        default:
            DEBUG_SERIAL.printf("Unknown command: 0x%02X\n", cmd);
            send_nack();
            break;
    }
}

// ============================================================================
// Packet Receive State Machine
// ============================================================================

void process_serial_byte(uint8_t byte) {
    switch (rx_state) {
        case RX_WAIT_START:
            if (byte == START_BYTE) {
                rx_state = RX_LEN_HIGH;
                rx_crc = 0;
            }
            break;

        case RX_LEN_HIGH:
            rx_payload_len = (uint16_t)byte << 8;
            rx_crc ^= byte;
            rx_state = RX_LEN_LOW;
            break;

        case RX_LEN_LOW:
            rx_payload_len |= byte;
            rx_crc ^= byte;
            if (rx_payload_len > MAX_PAYLOAD) {
                DEBUG_SERIAL.printf("Payload too large: %d\n", rx_payload_len);
                rx_state = RX_WAIT_START;
            } else {
                rx_state = RX_CMD;
            }
            break;

        case RX_CMD:
            rx_cmd = byte;
            rx_crc ^= byte;
            rx_payload_idx = 0;
            if (rx_payload_len > 0) {
                rx_state = RX_PAYLOAD;
            } else {
                rx_state = RX_CRC;
            }
            break;

        case RX_PAYLOAD:
            rx_buffer[rx_payload_idx++] = byte;
            rx_crc ^= byte;
            if (rx_payload_idx >= rx_payload_len) {
                rx_state = RX_CRC;
            }
            break;

        case RX_CRC:
            if (byte == rx_crc) {
                handle_command(rx_cmd, rx_buffer, rx_payload_len);
            } else {
                DEBUG_SERIAL.printf("CRC mismatch: got 0x%02X, expected 0x%02X\n",
                    byte, rx_crc);
            }
            rx_state = RX_WAIT_START;
            break;
    }
}

// ============================================================================
// Arduino Setup and Loop
// ============================================================================

void setup() {
    // Debug serial (USB)
    DEBUG_SERIAL.begin(115200);
    DEBUG_SERIAL.println();
    DEBUG_SERIAL.println("=== Flock WiFi Scanner ESP32 ===");
    DEBUG_SERIAL.printf("Firmware v%d.%d.%d\n",
        FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);

    // Flipper serial (GPIO 16/17)
    FLIPPER_SERIAL.begin(SERIAL_BAUD, SERIAL_8N1, 16, 17);
    DEBUG_SERIAL.println("UART initialized (GPIO 16/17)");

    // Initialize WiFi
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    DEBUG_SERIAL.println("WiFi initialized");

    // Default scan parameters
    scan_params.mode = 1;  // Active
    scan_params.channel = 0;  // Hop
    scan_params.dwell_time_ms = 100;
    scan_params.detect_hidden = true;
    scan_params.monitor_probes = false;
    scan_params.detect_deauths = false;
    scan_params.rssi_threshold = -90;

    DEBUG_SERIAL.println("Ready for Flipper connection...");
}

void loop() {
    // Process incoming serial data
    while (FLIPPER_SERIAL.available()) {
        uint8_t byte = FLIPPER_SERIAL.read();
        process_serial_byte(byte);
    }

    // Continuous scanning if enabled
    static unsigned long last_scan = 0;
    if (scanning) {
        unsigned long now = millis();

        // Scan at configured interval
        if (now - last_scan >= scan_params.dwell_time_ms + 2000) {
            do_wifi_scan();
            last_scan = now;
        }

        // Channel hopping
        if (scan_params.channel == 0) {
            static unsigned long last_hop = 0;
            if (now - last_hop >= scan_params.dwell_time_ms) {
                current_channel = (current_channel % 14) + 1;
                esp_wifi_set_channel(current_channel, WIFI_SECOND_CHAN_NONE);
                last_hop = now;
            }
        }
    }

    // Small delay to prevent tight loop
    delay(1);
}

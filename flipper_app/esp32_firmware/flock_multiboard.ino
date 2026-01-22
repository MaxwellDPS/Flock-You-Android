/**
 * Flock Multi-Board Firmware - ESP32 + CC1101 + nRF24L01+
 *
 * Comprehensive firmware for GINTBN-style multi-radio expansion boards
 * containing ESP32 (WiFi), CC1101 (Sub-GHz), and nRF24L01+ (2.4GHz).
 *
 * Compatible Boards:
 *   - GINTBN Flipper Zero Expansion Module
 *   - Similar ESP32+CC1101+nRF24 combo boards
 *   - Marauder-compatible boards (WiFi portion)
 *
 * Connections (GINTBN Standard):
 *   ESP32 TX (GPIO 17) -> Flipper RX (Pin 14)
 *   ESP32 RX (GPIO 16) -> Flipper TX (Pin 13)
 *   ESP32 GND -> Flipper GND
 *   ESP32 3.3V -> Flipper 3.3V (or USB power)
 *
 *   CC1101 (SPI):
 *     MOSI -> GPIO 23
 *     MISO -> GPIO 19
 *     SCK  -> GPIO 18
 *     CS   -> GPIO 5
 *     GDO0 -> GPIO 4
 *     GDO2 -> GPIO 2
 *
 *   nRF24L01+ (SPI - shared bus):
 *     MOSI -> GPIO 23 (shared)
 *     MISO -> GPIO 19 (shared)
 *     SCK  -> GPIO 18 (shared)
 *     CS   -> GPIO 15
 *     CE   -> GPIO 22
 *     IRQ  -> GPIO 21 (optional)
 *
 * Protocol: Binary protocol matching external_radio.h
 *   [0xAA][LEN_H][LEN_L][CMD][PAYLOAD...][CRC8]
 */

#include <WiFi.h>
#include <esp_wifi.h>
#include <SPI.h>

// ============================================================================
// Board Configuration - Adjust for your specific board variant
// ============================================================================

// GINTBN Standard Pin Configuration
#define PIN_CC1101_CS     5
#define PIN_CC1101_GDO0   4
#define PIN_CC1101_GDO2   2

#define PIN_NRF24_CS      15
#define PIN_NRF24_CE      22
#define PIN_NRF24_IRQ     21

// SPI Pins (shared between CC1101 and nRF24)
#define PIN_SPI_MOSI      23
#define PIN_SPI_MISO      19
#define PIN_SPI_SCK       18

// UART to Flipper
#define PIN_FLIPPER_RX    16
#define PIN_FLIPPER_TX    17

// ============================================================================
// Firmware Configuration
// ============================================================================

#define SERIAL_BAUD 115200
#define FLIPPER_SERIAL Serial2
#define DEBUG_SERIAL Serial

#define FIRMWARE_VERSION_MAJOR 2
#define FIRMWARE_VERSION_MINOR 0
#define FIRMWARE_VERSION_PATCH 0
#define FIRMWARE_NAME "FlockMulti"

// Protocol
#define START_BYTE 0xAA
#define MAX_PAYLOAD 512

// ============================================================================
// Commands (Flipper -> ESP32)
// ============================================================================

enum Command {
    // System commands
    CMD_PING = 0x01,
    CMD_GET_INFO = 0x02,
    CMD_RESET = 0x03,

    // WiFi commands (0x10-0x1F)
    CMD_WIFI_SCAN_START = 0x10,
    CMD_WIFI_SCAN_STOP = 0x11,
    CMD_WIFI_SET_CHANNEL = 0x12,
    CMD_WIFI_SET_MODE = 0x13,
    CMD_WIFI_DEAUTH = 0x14,
    CMD_WIFI_PROBE = 0x15,

    // Sub-GHz / CC1101 commands (0x20-0x2F)
    CMD_SUBGHZ_SET_FREQ = 0x20,
    CMD_SUBGHZ_SET_MOD = 0x21,
    CMD_SUBGHZ_RX_START = 0x22,
    CMD_SUBGHZ_RX_STOP = 0x23,
    CMD_SUBGHZ_TX_START = 0x24,
    CMD_SUBGHZ_TX_STOP = 0x25,
    CMD_SUBGHZ_GET_RSSI = 0x26,
    CMD_SUBGHZ_SET_PRESET = 0x27,

    // BLE / nRF24 commands (0x30-0x3F)
    CMD_BLE_SCAN_START = 0x30,
    CMD_BLE_SCAN_STOP = 0x31,
    CMD_BLE_SET_CHANNEL = 0x32,

    // nRF24 specific commands (0x40-0x4F)
    CMD_NRF24_SNIFF_START = 0x40,
    CMD_NRF24_SNIFF_STOP = 0x41,
    CMD_NRF24_SET_CHANNEL = 0x42,
    CMD_NRF24_SET_ADDRESS = 0x43,
    CMD_NRF24_TX = 0x44,
    CMD_NRF24_CONFIG = 0x45,
    CMD_NRF24_MOUSEJACK = 0x46,
};

// ============================================================================
// Responses (ESP32 -> Flipper)
// ============================================================================

enum Response {
    RESP_ACK = 0x01,
    RESP_NACK = 0x02,
    RESP_INFO = 0x03,

    // WiFi responses
    RESP_WIFI_NETWORK = 0x10,
    RESP_WIFI_SCAN_DONE = 0x11,
    RESP_WIFI_PROBE = 0x12,
    RESP_WIFI_DEAUTH = 0x13,
    RESP_WIFI_RAW = 0x14,

    // Sub-GHz responses
    RESP_SUBGHZ_SIGNAL = 0x20,
    RESP_SUBGHZ_RSSI = 0x21,
    RESP_SUBGHZ_RAW = 0x22,

    // BLE responses
    RESP_BLE_DEVICE = 0x30,
    RESP_BLE_SCAN_DONE = 0x31,

    // nRF24 responses
    RESP_NRF24_PACKET = 0x40,
    RESP_NRF24_SNIFF_DONE = 0x41,
    RESP_NRF24_TX_DONE = 0x42,
};

// Radio type for multi-board
enum RadioType {
    RADIO_NONE = 0,
    RADIO_ESP32 = 1,
    RADIO_ESP8266 = 2,
    RADIO_CC1101 = 3,
    RADIO_NRF24 = 4,
    RADIO_MULTIBOARD = 10,  // Multi-radio board
};

// Capability flags
#define CAP_WIFI_SCAN      (1 << 0)
#define CAP_WIFI_MONITOR   (1 << 1)
#define CAP_WIFI_DEAUTH    (1 << 2)
#define CAP_WIFI_INJECT    (1 << 3)
#define CAP_SUBGHZ_RX      (1 << 4)
#define CAP_SUBGHZ_TX      (1 << 5)
#define CAP_BLE_SCAN       (1 << 6)
#define CAP_BLE_ADV        (1 << 7)
#define CAP_NRF24_SNIFF    (1 << 8)
#define CAP_NRF24_INJECT   (1 << 9)
#define CAP_NRF24_MOUSEJACK (1 << 10)

// ============================================================================
// Data Structures
// ============================================================================

struct __attribute__((packed)) RadioInfo {
    uint8_t type;
    uint8_t version_major;
    uint8_t version_minor;
    uint8_t version_patch;
    char name[16];
    uint32_t capabilities;
};

struct __attribute__((packed)) WifiNetwork {
    char ssid[33];
    uint8_t bssid[6];
    int8_t rssi;
    uint8_t channel;
    uint8_t security;
    uint8_t hidden;
    uint16_t frame_count;
};

struct __attribute__((packed)) WifiProbe {
    uint8_t sta_mac[6];
    char ssid[33];
    int8_t rssi;
    uint8_t channel;
    uint32_t timestamp;
};

struct __attribute__((packed)) SubGhzSignal {
    uint32_t frequency;
    int8_t rssi;
    uint8_t modulation;
    uint16_t duration_ms;
    uint16_t data_len;
    // Raw data follows
};

struct __attribute__((packed)) Nrf24Packet {
    uint8_t address[5];
    uint8_t channel;
    int8_t rssi;
    uint8_t payload_len;
    // Payload follows
};

// ============================================================================
// Global State
// ============================================================================

// Radio availability
bool cc1101_available = false;
bool nrf24_available = false;

// WiFi state
bool wifi_scanning = false;
uint8_t wifi_channel = 0;

// CC1101 state
bool subghz_rx_active = false;
uint32_t subghz_frequency = 433920000;

// nRF24 state
bool nrf24_sniffing = false;
uint8_t nrf24_channel = 0;
uint8_t nrf24_address[5] = {0xAA, 0x55, 0xAA, 0x55, 0xAA};

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
// CC1101 Register Definitions
// ============================================================================

// CC1101 SPI Commands
#define CC1101_SRES     0x30  // Reset
#define CC1101_SRX      0x34  // RX mode
#define CC1101_STX      0x35  // TX mode
#define CC1101_SIDLE    0x36  // Idle mode
#define CC1101_SFRX     0x3A  // Flush RX FIFO
#define CC1101_SFTX     0x3B  // Flush TX FIFO
#define CC1101_SNOP     0x3D  // No operation

// CC1101 Registers
#define CC1101_FREQ2    0x0D
#define CC1101_FREQ1    0x0E
#define CC1101_FREQ0    0x0F
#define CC1101_RSSI     0x34  // Status register
#define CC1101_RXBYTES  0x3B  // RX FIFO count
#define CC1101_TXFIFO   0x3F  // TX FIFO
#define CC1101_RXFIFO   0x3F  // RX FIFO (burst read)

// ============================================================================
// nRF24L01+ Register Definitions
// ============================================================================

// nRF24 Commands
#define NRF24_R_REGISTER    0x00
#define NRF24_W_REGISTER    0x20
#define NRF24_R_RX_PAYLOAD  0x61
#define NRF24_W_TX_PAYLOAD  0xA0
#define NRF24_FLUSH_TX      0xE1
#define NRF24_FLUSH_RX      0xE2
#define NRF24_NOP           0xFF

// nRF24 Registers
#define NRF24_CONFIG        0x00
#define NRF24_EN_AA         0x01
#define NRF24_RF_CH         0x05
#define NRF24_RF_SETUP      0x06
#define NRF24_STATUS        0x07
#define NRF24_RX_ADDR_P0    0x0A
#define NRF24_TX_ADDR       0x10
#define NRF24_RX_PW_P0      0x11
#define NRF24_FIFO_STATUS   0x17
#define NRF24_DYNPD         0x1C
#define NRF24_FEATURE       0x1D

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

// ============================================================================
// CC1101 SPI Functions
// ============================================================================

void cc1101_select() {
    digitalWrite(PIN_CC1101_CS, LOW);
}

void cc1101_deselect() {
    digitalWrite(PIN_CC1101_CS, HIGH);
}

uint8_t cc1101_strobe(uint8_t strobe) {
    cc1101_select();
    uint8_t status = SPI.transfer(strobe);
    cc1101_deselect();
    return status;
}

void cc1101_write_reg(uint8_t addr, uint8_t value) {
    cc1101_select();
    SPI.transfer(addr);
    SPI.transfer(value);
    cc1101_deselect();
}

uint8_t cc1101_read_reg(uint8_t addr) {
    cc1101_select();
    SPI.transfer(addr | 0x80);  // Read bit
    uint8_t value = SPI.transfer(0);
    cc1101_deselect();
    return value;
}

uint8_t cc1101_read_status(uint8_t addr) {
    cc1101_select();
    SPI.transfer(addr | 0xC0);  // Status read
    uint8_t value = SPI.transfer(0);
    cc1101_deselect();
    return value;
}

bool cc1101_init() {
    pinMode(PIN_CC1101_CS, OUTPUT);
    pinMode(PIN_CC1101_GDO0, INPUT);
    pinMode(PIN_CC1101_GDO2, INPUT);
    cc1101_deselect();

    // Reset
    cc1101_strobe(CC1101_SRES);
    delay(10);

    // Check if CC1101 responds
    cc1101_strobe(CC1101_SIDLE);
    delay(1);

    uint8_t version = cc1101_read_status(0x31);  // VERSION register
    if (version == 0x14 || version == 0x04) {
        DEBUG_SERIAL.printf("CC1101 detected (version 0x%02X)\n", version);
        return true;
    }

    DEBUG_SERIAL.println("CC1101 not detected");
    return false;
}

void cc1101_set_frequency(uint32_t freq_hz) {
    // CC1101 frequency formula: f = (FREQ * 26MHz) / 2^16
    // FREQ = (f * 2^16) / 26MHz
    uint32_t freq_reg = (uint32_t)((float)freq_hz / 26000000.0 * 65536.0);

    cc1101_strobe(CC1101_SIDLE);
    cc1101_write_reg(CC1101_FREQ2, (freq_reg >> 16) & 0xFF);
    cc1101_write_reg(CC1101_FREQ1, (freq_reg >> 8) & 0xFF);
    cc1101_write_reg(CC1101_FREQ0, freq_reg & 0xFF);

    subghz_frequency = freq_hz;
    DEBUG_SERIAL.printf("CC1101 frequency set to %lu Hz\n", freq_hz);
}

int8_t cc1101_get_rssi() {
    uint8_t rssi_raw = cc1101_read_status(CC1101_RSSI);
    int8_t rssi_dbm;
    if (rssi_raw >= 128) {
        rssi_dbm = (int8_t)((rssi_raw - 256) / 2 - 74);
    } else {
        rssi_dbm = (int8_t)(rssi_raw / 2 - 74);
    }
    return rssi_dbm;
}

void cc1101_start_rx() {
    cc1101_strobe(CC1101_SFRX);
    cc1101_strobe(CC1101_SRX);
    subghz_rx_active = true;
    DEBUG_SERIAL.println("CC1101 RX started");
}

void cc1101_stop_rx() {
    cc1101_strobe(CC1101_SIDLE);
    subghz_rx_active = false;
    DEBUG_SERIAL.println("CC1101 RX stopped");
}

// ============================================================================
// nRF24L01+ SPI Functions
// ============================================================================

void nrf24_select() {
    digitalWrite(PIN_NRF24_CS, LOW);
}

void nrf24_deselect() {
    digitalWrite(PIN_NRF24_CS, HIGH);
}

void nrf24_ce_high() {
    digitalWrite(PIN_NRF24_CE, HIGH);
}

void nrf24_ce_low() {
    digitalWrite(PIN_NRF24_CE, LOW);
}

uint8_t nrf24_read_reg(uint8_t reg) {
    nrf24_select();
    SPI.transfer(NRF24_R_REGISTER | reg);
    uint8_t value = SPI.transfer(0xFF);
    nrf24_deselect();
    return value;
}

void nrf24_write_reg(uint8_t reg, uint8_t value) {
    nrf24_select();
    SPI.transfer(NRF24_W_REGISTER | reg);
    SPI.transfer(value);
    nrf24_deselect();
}

void nrf24_write_reg_multi(uint8_t reg, const uint8_t* data, size_t len) {
    nrf24_select();
    SPI.transfer(NRF24_W_REGISTER | reg);
    for (size_t i = 0; i < len; i++) {
        SPI.transfer(data[i]);
    }
    nrf24_deselect();
}

void nrf24_read_payload(uint8_t* data, size_t len) {
    nrf24_select();
    SPI.transfer(NRF24_R_RX_PAYLOAD);
    for (size_t i = 0; i < len; i++) {
        data[i] = SPI.transfer(0xFF);
    }
    nrf24_deselect();
}

void nrf24_write_payload(const uint8_t* data, size_t len) {
    nrf24_select();
    SPI.transfer(NRF24_W_TX_PAYLOAD);
    for (size_t i = 0; i < len; i++) {
        SPI.transfer(data[i]);
    }
    nrf24_deselect();
}

void nrf24_flush_rx() {
    nrf24_select();
    SPI.transfer(NRF24_FLUSH_RX);
    nrf24_deselect();
}

void nrf24_flush_tx() {
    nrf24_select();
    SPI.transfer(NRF24_FLUSH_TX);
    nrf24_deselect();
}

bool nrf24_init() {
    pinMode(PIN_NRF24_CS, OUTPUT);
    pinMode(PIN_NRF24_CE, OUTPUT);
    pinMode(PIN_NRF24_IRQ, INPUT);
    nrf24_deselect();
    nrf24_ce_low();

    delay(5);  // Power on reset

    // Check if nRF24 responds
    nrf24_write_reg(NRF24_CONFIG, 0x0C);  // Power up, RX mode
    delay(2);

    uint8_t config = nrf24_read_reg(NRF24_CONFIG);
    if (config == 0x0C || config == 0x0E || config == 0x0F) {
        DEBUG_SERIAL.printf("nRF24L01+ detected (config 0x%02X)\n", config);

        // Configure for promiscuous sniffing
        nrf24_write_reg(NRF24_EN_AA, 0x00);       // Disable auto-ack
        nrf24_write_reg(NRF24_DYNPD, 0x00);       // Disable dynamic payload
        nrf24_write_reg(NRF24_RF_SETUP, 0x0F);    // 2Mbps, max power
        nrf24_write_reg(NRF24_RX_PW_P0, 32);      // 32 byte payload
        nrf24_flush_rx();
        nrf24_flush_tx();

        return true;
    }

    DEBUG_SERIAL.println("nRF24L01+ not detected");
    return false;
}

void nrf24_set_channel(uint8_t channel) {
    nrf24_ce_low();
    nrf24_write_reg(NRF24_RF_CH, channel & 0x7F);
    nrf24_channel = channel;
    DEBUG_SERIAL.printf("nRF24 channel set to %d\n", channel);
}

void nrf24_set_address(const uint8_t* addr) {
    memcpy(nrf24_address, addr, 5);
    nrf24_write_reg_multi(NRF24_RX_ADDR_P0, addr, 5);
    nrf24_write_reg_multi(NRF24_TX_ADDR, addr, 5);
    DEBUG_SERIAL.printf("nRF24 address set to %02X:%02X:%02X:%02X:%02X\n",
        addr[0], addr[1], addr[2], addr[3], addr[4]);
}

void nrf24_start_rx() {
    nrf24_write_reg(NRF24_CONFIG, 0x0F);  // Power up, RX mode, enable CRC
    nrf24_ce_high();
    nrf24_sniffing = true;
    DEBUG_SERIAL.println("nRF24 RX started");
}

void nrf24_stop_rx() {
    nrf24_ce_low();
    nrf24_sniffing = false;
    DEBUG_SERIAL.println("nRF24 RX stopped");
}

bool nrf24_data_available() {
    uint8_t status = nrf24_read_reg(NRF24_STATUS);
    return (status & 0x40) != 0;  // RX_DR flag
}

void nrf24_tx_packet(const uint8_t* data, size_t len) {
    nrf24_ce_low();
    nrf24_write_reg(NRF24_CONFIG, 0x0E);  // Power up, TX mode
    nrf24_flush_tx();
    nrf24_write_payload(data, len);
    nrf24_ce_high();
    delayMicroseconds(20);
    nrf24_ce_low();

    // Wait for TX complete or timeout
    uint32_t start = millis();
    while (millis() - start < 50) {
        uint8_t status = nrf24_read_reg(NRF24_STATUS);
        if (status & 0x20) {  // TX_DS
            nrf24_write_reg(NRF24_STATUS, 0x20);  // Clear flag
            send_packet(RESP_NRF24_TX_DONE, nullptr, 0);
            DEBUG_SERIAL.println("nRF24 TX complete");
            return;
        }
        if (status & 0x10) {  // MAX_RT
            nrf24_write_reg(NRF24_STATUS, 0x10);  // Clear flag
            nrf24_flush_tx();
            send_nack();
            DEBUG_SERIAL.println("nRF24 TX max retries");
            return;
        }
    }
    send_nack();
}

// ============================================================================
// WiFi Functions
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

void wifi_do_scan(bool detect_hidden, int8_t rssi_threshold) {
    DEBUG_SERIAL.println("Starting WiFi scan...");

    int n = WiFi.scanNetworks(false, detect_hidden);

    DEBUG_SERIAL.printf("Found %d networks\n", n);

    for (int i = 0; i < n; i++) {
        if (WiFi.RSSI(i) < rssi_threshold) continue;

        WifiNetwork network;
        memset(&network, 0, sizeof(network));

        String ssid = WiFi.SSID(i);
        strncpy(network.ssid, ssid.c_str(), 32);
        network.ssid[32] = '\0';

        uint8_t* bssid = WiFi.BSSID(i);
        memcpy(network.bssid, bssid, 6);

        network.rssi = WiFi.RSSI(i);
        network.channel = WiFi.channel(i);
        network.security = convert_auth_mode(WiFi.encryptionType(i));
        network.hidden = (ssid.length() == 0) ? 1 : 0;
        network.frame_count = 1;

        send_packet(RESP_WIFI_NETWORK, (uint8_t*)&network, sizeof(network));

        DEBUG_SERIAL.printf("  %s (%d dBm, ch %d)\n",
            network.ssid[0] ? network.ssid : "<hidden>",
            network.rssi, network.channel);
    }

    send_packet(RESP_WIFI_SCAN_DONE, nullptr, 0);
    DEBUG_SERIAL.println("Scan complete");
}

// ============================================================================
// Info Response
// ============================================================================

void send_info() {
    RadioInfo info;
    memset(&info, 0, sizeof(info));

    info.type = RADIO_MULTIBOARD;
    info.version_major = FIRMWARE_VERSION_MAJOR;
    info.version_minor = FIRMWARE_VERSION_MINOR;
    info.version_patch = FIRMWARE_VERSION_PATCH;
    strncpy(info.name, FIRMWARE_NAME, sizeof(info.name) - 1);

    // Build capabilities based on detected hardware
    info.capabilities = CAP_WIFI_SCAN | CAP_WIFI_MONITOR;

    if (cc1101_available) {
        info.capabilities |= CAP_SUBGHZ_RX | CAP_SUBGHZ_TX;
    }

    if (nrf24_available) {
        info.capabilities |= CAP_BLE_SCAN | CAP_NRF24_SNIFF | CAP_NRF24_INJECT | CAP_NRF24_MOUSEJACK;
    }

    DEBUG_SERIAL.printf("Reporting capabilities: 0x%08X\n", info.capabilities);
    send_packet(RESP_INFO, (uint8_t*)&info, sizeof(info));
}

// ============================================================================
// Command Handler
// ============================================================================

void handle_command(uint8_t cmd, const uint8_t* payload, size_t len) {
    DEBUG_SERIAL.printf("Received cmd: 0x%02X, len: %d\n", cmd, len);

    switch (cmd) {
        // System commands
        case CMD_PING:
            send_ack();
            break;

        case CMD_GET_INFO:
            send_info();
            break;

        case CMD_RESET:
            send_ack();
            delay(100);
            ESP.restart();
            break;

        // WiFi commands
        case CMD_WIFI_SCAN_START: {
            bool detect_hidden = true;
            int8_t rssi_thresh = -90;
            if (len >= 2) {
                detect_hidden = payload[0] != 0;
                rssi_thresh = (int8_t)payload[1];
            }
            wifi_scanning = true;
            send_ack();
            wifi_do_scan(detect_hidden, rssi_thresh);
            wifi_scanning = false;
            break;
        }

        case CMD_WIFI_SCAN_STOP:
            wifi_scanning = false;
            send_ack();
            break;

        case CMD_WIFI_SET_CHANNEL:
            if (len >= 1) {
                wifi_channel = payload[0];
                if (wifi_channel >= 1 && wifi_channel <= 14) {
                    esp_wifi_set_channel(wifi_channel, WIFI_SECOND_CHAN_NONE);
                }
            }
            send_ack();
            break;

        // Sub-GHz commands (CC1101)
        case CMD_SUBGHZ_SET_FREQ:
            if (cc1101_available && len >= 4) {
                uint32_t freq = ((uint32_t)payload[0] << 24) |
                               ((uint32_t)payload[1] << 16) |
                               ((uint32_t)payload[2] << 8) |
                               payload[3];
                cc1101_set_frequency(freq);
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_SUBGHZ_RX_START:
            if (cc1101_available) {
                cc1101_start_rx();
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_SUBGHZ_RX_STOP:
            if (cc1101_available) {
                cc1101_stop_rx();
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_SUBGHZ_GET_RSSI:
            if (cc1101_available) {
                int8_t rssi = cc1101_get_rssi();
                send_packet(RESP_SUBGHZ_RSSI, (uint8_t*)&rssi, 1);
            } else {
                send_nack();
            }
            break;

        // nRF24 commands
        case CMD_NRF24_SNIFF_START:
            if (nrf24_available) {
                nrf24_start_rx();
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_NRF24_SNIFF_STOP:
            if (nrf24_available) {
                nrf24_stop_rx();
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_NRF24_SET_CHANNEL:
            if (nrf24_available && len >= 1) {
                nrf24_set_channel(payload[0]);
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_NRF24_SET_ADDRESS:
            if (nrf24_available && len >= 5) {
                nrf24_set_address(payload);
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_NRF24_TX:
            if (nrf24_available && len >= 1) {
                nrf24_tx_packet(payload, len);
            } else {
                send_nack();
            }
            break;

        case CMD_NRF24_CONFIG:
            if (nrf24_available && len >= 6) {
                // [channel][addr0][addr1][addr2][addr3][addr4]
                nrf24_set_channel(payload[0]);
                nrf24_set_address(&payload[1]);
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_NRF24_MOUSEJACK:
            // MouseJack keystroke injection
            // Payload: [address 5B][keycode count][keycodes...]
            if (nrf24_available && len >= 7) {
                nrf24_set_address(payload);
                uint8_t keycode_count = payload[5];
                if (keycode_count > 0 && len >= 6 + keycode_count) {
                    // Build HID payload and transmit
                    // Format varies by target device - this is a generic implementation
                    for (int i = 0; i < keycode_count; i++) {
                        uint8_t hid_payload[10] = {0};
                        hid_payload[0] = 0x00;  // Device type
                        hid_payload[2] = payload[6 + i];  // Keycode
                        nrf24_tx_packet(hid_payload, sizeof(hid_payload));
                        delay(10);
                        // Key release
                        memset(hid_payload, 0, sizeof(hid_payload));
                        nrf24_tx_packet(hid_payload, sizeof(hid_payload));
                        delay(10);
                    }
                    send_ack();
                } else {
                    send_nack();
                }
            } else {
                send_nack();
            }
            break;

        // BLE scan (via nRF24 in promiscuous mode)
        case CMD_BLE_SCAN_START:
            if (nrf24_available) {
                // BLE advertising uses channels 37, 38, 39 (2402, 2426, 2480 MHz)
                // nRF24 can sniff these by setting appropriate channel
                nrf24_set_channel(2);   // ~2402 MHz (BLE ch 37)
                nrf24_start_rx();
                send_ack();
            } else {
                send_nack();
            }
            break;

        case CMD_BLE_SCAN_STOP:
            if (nrf24_available) {
                nrf24_stop_rx();
                send_ack();
            } else {
                send_nack();
            }
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
// Background Tasks
// ============================================================================

void check_nrf24_rx() {
    if (!nrf24_available || !nrf24_sniffing) return;

    if (nrf24_data_available()) {
        Nrf24Packet pkt;
        memcpy(pkt.address, nrf24_address, 5);
        pkt.channel = nrf24_channel;
        pkt.rssi = -50;  // nRF24 doesn't provide RSSI
        pkt.payload_len = 32;

        uint8_t payload[32];
        nrf24_read_payload(payload, 32);

        // Clear RX flag
        nrf24_write_reg(NRF24_STATUS, 0x40);

        // Send packet to Flipper
        uint8_t buffer[sizeof(Nrf24Packet) + 32];
        memcpy(buffer, &pkt, sizeof(Nrf24Packet));
        memcpy(buffer + sizeof(Nrf24Packet), payload, 32);
        send_packet(RESP_NRF24_PACKET, buffer, sizeof(buffer));

        DEBUG_SERIAL.println("nRF24 packet received");
    }
}

void check_cc1101_rx() {
    if (!cc1101_available || !subghz_rx_active) return;

    // Check GDO0 for packet received
    if (digitalRead(PIN_CC1101_GDO0)) {
        uint8_t rx_bytes = cc1101_read_status(CC1101_RXBYTES);
        if (rx_bytes > 0 && rx_bytes < 64) {
            SubGhzSignal sig;
            sig.frequency = subghz_frequency;
            sig.rssi = cc1101_get_rssi();
            sig.modulation = 0;  // OOK
            sig.duration_ms = 0;
            sig.data_len = rx_bytes;

            // Read FIFO
            uint8_t buffer[sizeof(SubGhzSignal) + 64];
            memcpy(buffer, &sig, sizeof(SubGhzSignal));

            cc1101_select();
            SPI.transfer(CC1101_RXFIFO | 0xC0);  // Burst read
            for (int i = 0; i < rx_bytes; i++) {
                buffer[sizeof(SubGhzSignal) + i] = SPI.transfer(0);
            }
            cc1101_deselect();

            send_packet(RESP_SUBGHZ_SIGNAL, buffer, sizeof(SubGhzSignal) + rx_bytes);
            DEBUG_SERIAL.printf("CC1101 signal received (RSSI: %d)\n", sig.rssi);
        }
        cc1101_strobe(CC1101_SFRX);  // Flush RX FIFO
        cc1101_strobe(CC1101_SRX);   // Re-enter RX
    }
}

// ============================================================================
// Arduino Setup and Loop
// ============================================================================

void setup() {
    // Debug serial (USB)
    DEBUG_SERIAL.begin(115200);
    delay(100);
    DEBUG_SERIAL.println();
    DEBUG_SERIAL.println("===========================================");
    DEBUG_SERIAL.println("  Flock Multi-Board Firmware");
    DEBUG_SERIAL.println("  ESP32 + CC1101 + nRF24L01+");
    DEBUG_SERIAL.printf("  Version %d.%d.%d\n",
        FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
    DEBUG_SERIAL.println("===========================================");

    // Initialize SPI
    SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI);
    SPI.setFrequency(4000000);  // 4 MHz
    DEBUG_SERIAL.println("SPI initialized");

    // Flipper serial (GPIO 16/17)
    FLIPPER_SERIAL.begin(SERIAL_BAUD, SERIAL_8N1, PIN_FLIPPER_RX, PIN_FLIPPER_TX);
    DEBUG_SERIAL.println("UART initialized (GPIO 16/17)");

    // Initialize WiFi
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    DEBUG_SERIAL.println("WiFi initialized");

    // Detect and initialize CC1101
    cc1101_available = cc1101_init();
    if (cc1101_available) {
        cc1101_set_frequency(433920000);  // Default to 433.92 MHz
    }

    // Detect and initialize nRF24L01+
    nrf24_available = nrf24_init();
    if (nrf24_available) {
        nrf24_set_channel(76);  // Default channel
    }

    // Summary
    DEBUG_SERIAL.println("-------------------------------------------");
    DEBUG_SERIAL.printf("WiFi:   READY\n");
    DEBUG_SERIAL.printf("CC1101: %s\n", cc1101_available ? "READY" : "NOT FOUND");
    DEBUG_SERIAL.printf("nRF24:  %s\n", nrf24_available ? "READY" : "NOT FOUND");
    DEBUG_SERIAL.println("-------------------------------------------");
    DEBUG_SERIAL.println("Ready for Flipper connection...");
}

void loop() {
    // Process incoming serial data from Flipper
    while (FLIPPER_SERIAL.available()) {
        uint8_t byte = FLIPPER_SERIAL.read();
        process_serial_byte(byte);
    }

    // Check for incoming RF data
    check_nrf24_rx();
    check_cc1101_rx();

    // Small delay to prevent tight loop
    delay(1);
}

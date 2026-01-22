#pragma once

#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_serial.h>

// ============================================================================
// External Radio Manager
// ============================================================================
// Manages communication with external radio modules connected via UART/GPIO:
// - ESP32 for WiFi scanning
// - CC1101 modules for extended Sub-GHz
// - nRF24 modules for extended BLE
//
// The Flipper Zero GPIO header provides:
// - Pin 13 (TX) / Pin 14 (RX) - USART1
// - Pin 15 (TX) / Pin 16 (RX) - USART2 (LPUART)
// - 3.3V and GND

// ============================================================================
// Radio Types
// ============================================================================

typedef enum {
    ExternalRadioNone = 0,
    ExternalRadioESP32,         // ESP32 for WiFi (most common)
    ExternalRadioESP8266,       // ESP8266 for WiFi (legacy)
    ExternalRadioCC1101,        // CC1101 for extended Sub-GHz
    ExternalRadioNRF24,         // nRF24L01+ for 2.4GHz
    ExternalRadioCC2500,        // CC2500 for 2.4GHz
    ExternalRadioSX1276,        // SX1276/78 for LoRa
    ExternalRadioMultiBoard = 10, // Multi-radio board (ESP32+CC1101+nRF24)
} ExternalRadioType;

typedef enum {
    ExternalRadioStateDisconnected,
    ExternalRadioStateConnecting,
    ExternalRadioStateConnected,
    ExternalRadioStateError,
} ExternalRadioState;

// ============================================================================
// UART Protocol for External Radios
// ============================================================================
// Simple binary protocol for communication:
// [START][LEN_H][LEN_L][CMD][PAYLOAD...][CRC8]
// START = 0xAA
// LEN = payload length (16-bit, big-endian)
// CMD = command byte
// CRC8 = XOR of all bytes except START

#define EXT_RADIO_START_BYTE 0xAA
#define EXT_RADIO_MAX_PAYLOAD 512
#define EXT_RADIO_TIMEOUT_MS 1000
#define EXT_RADIO_HEARTBEAT_MS 5000

// Command types (Flipper -> External)
typedef enum {
    ExtRadioCmdPing = 0x01,
    ExtRadioCmdGetInfo = 0x02,
    ExtRadioCmdReset = 0x03,

    // WiFi commands (0x10-0x1F)
    ExtRadioCmdWifiScanStart = 0x10,
    ExtRadioCmdWifiScanStop = 0x11,
    ExtRadioCmdWifiSetChannel = 0x12,
    ExtRadioCmdWifiSetMode = 0x13,
    ExtRadioCmdWifiDeauth = 0x14,  // For testing only
    ExtRadioCmdWifiProbe = 0x15,

    // Sub-GHz commands for external CC1101 (0x20-0x2F)
    ExtRadioCmdSubGhzSetFreq = 0x20,
    ExtRadioCmdSubGhzSetMod = 0x21,
    ExtRadioCmdSubGhzRxStart = 0x22,
    ExtRadioCmdSubGhzRxStop = 0x23,
    ExtRadioCmdSubGhzTxStart = 0x24,
    ExtRadioCmdSubGhzTxStop = 0x25,
    ExtRadioCmdSubGhzGetRssi = 0x26,
    ExtRadioCmdSubGhzSetPreset = 0x27,

    // BLE commands for external nRF24/CC2500 (0x30-0x3F)
    ExtRadioCmdBleScanStart = 0x30,
    ExtRadioCmdBleScanStop = 0x31,
    ExtRadioCmdBleSetChannel = 0x32,

    // nRF24 specific commands (0x40-0x4F)
    ExtRadioCmdNrf24SniffStart = 0x40,
    ExtRadioCmdNrf24SniffStop = 0x41,
    ExtRadioCmdNrf24SetChannel = 0x42,
    ExtRadioCmdNrf24SetAddress = 0x43,
    ExtRadioCmdNrf24Tx = 0x44,
    ExtRadioCmdNrf24Config = 0x45,
    ExtRadioCmdNrf24Mousejack = 0x46,

    // Zigbee commands (0x50-0x5F)
    ExtRadioCmdZigbeeScanStart = 0x50,
    ExtRadioCmdZigbeeScanStop = 0x51,
    ExtRadioCmdZigbeeBeacon = 0x52,
    ExtRadioCmdZigbeeSetChannel = 0x53,
} ExtRadioCommand;

// Response types (External -> Flipper)
typedef enum {
    ExtRadioRespAck = 0x01,
    ExtRadioRespNack = 0x02,
    ExtRadioRespInfo = 0x03,

    // WiFi responses (0x10-0x1F)
    ExtRadioRespWifiNetwork = 0x10,
    ExtRadioRespWifiScanDone = 0x11,
    ExtRadioRespWifiProbe = 0x12,
    ExtRadioRespWifiDeauth = 0x13,
    ExtRadioRespWifiRaw = 0x14,

    // Sub-GHz responses (0x20-0x2F)
    ExtRadioRespSubGhzSignal = 0x20,
    ExtRadioRespSubGhzRssi = 0x21,
    ExtRadioRespSubGhzRaw = 0x22,

    // BLE responses (0x30-0x3F)
    ExtRadioRespBleDevice = 0x30,
    ExtRadioRespBleScanDone = 0x31,

    // nRF24 responses (0x40-0x4F)
    ExtRadioRespNrf24Packet = 0x40,
    ExtRadioRespNrf24SniffDone = 0x41,
    ExtRadioRespNrf24TxDone = 0x42,
} ExtRadioResponse;

// ============================================================================
// Data Structures
// ============================================================================

// Radio info response
typedef struct __attribute__((packed)) {
    ExternalRadioType type;
    uint8_t version_major;
    uint8_t version_minor;
    uint8_t version_patch;
    char name[16];
    uint32_t capabilities;
} ExtRadioInfo;

// Capability flags
#define EXT_RADIO_CAP_WIFI_SCAN      (1 << 0)
#define EXT_RADIO_CAP_WIFI_MONITOR   (1 << 1)
#define EXT_RADIO_CAP_WIFI_DEAUTH    (1 << 2)
#define EXT_RADIO_CAP_WIFI_INJECT    (1 << 3)
#define EXT_RADIO_CAP_SUBGHZ_RX      (1 << 4)
#define EXT_RADIO_CAP_SUBGHZ_TX      (1 << 5)
#define EXT_RADIO_CAP_BLE_SCAN       (1 << 6)
#define EXT_RADIO_CAP_BLE_ADV        (1 << 7)
#define EXT_RADIO_CAP_NRF24_SNIFF    (1 << 8)
#define EXT_RADIO_CAP_NRF24_INJECT   (1 << 9)
#define EXT_RADIO_CAP_NRF24_MOUSEJACK (1 << 10)
#define EXT_RADIO_CAP_ZIGBEE         (1 << 11)

// WiFi network from external radio
typedef struct __attribute__((packed)) {
    char ssid[33];
    uint8_t bssid[6];
    int8_t rssi;
    uint8_t channel;
    uint8_t security;
    uint8_t hidden;
    uint16_t frame_count;   // Beacon/probe count during scan
} ExtWifiNetwork;

// WiFi probe request
typedef struct __attribute__((packed)) {
    uint8_t sta_mac[6];
    char ssid[33];
    int8_t rssi;
    uint8_t channel;
    uint32_t timestamp;
} ExtWifiProbe;

// WiFi deauth detected
typedef struct __attribute__((packed)) {
    uint8_t bssid[6];
    uint8_t target_mac[6];
    uint8_t reason;
    int8_t rssi;
    uint32_t count;
} ExtWifiDeauth;

// Sub-GHz signal from external CC1101
typedef struct __attribute__((packed)) {
    uint32_t frequency;
    int8_t rssi;
    uint8_t modulation;
    uint16_t duration_ms;
    uint16_t data_len;
    // Raw data follows
} ExtSubGhzSignal;

// nRF24 packet from external module
typedef struct __attribute__((packed)) {
    uint8_t address[5];
    uint8_t channel;
    int8_t rssi;
    uint8_t payload_len;
    // Payload follows
} ExtNrf24Packet;

// ============================================================================
// External Radio Manager
// ============================================================================

typedef struct ExternalRadioManager ExternalRadioManager;

// Callback types
typedef void (*ExtRadioConnectCallback)(ExternalRadioType type, void* context);
typedef void (*ExtRadioDisconnectCallback)(void* context);
typedef void (*ExtRadioDataCallback)(uint8_t cmd, const uint8_t* data, size_t len, void* context);

// Configuration
typedef struct {
    FuriHalSerialId serial_id;     // Which UART to use
    uint32_t baud_rate;            // Default: 115200

    ExtRadioConnectCallback on_connect;
    ExtRadioDisconnectCallback on_disconnect;
    ExtRadioDataCallback on_data;
    void* callback_context;
} ExternalRadioConfig;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate external radio manager.
 */
ExternalRadioManager* external_radio_alloc(void);

/**
 * Free external radio manager.
 */
void external_radio_free(ExternalRadioManager* manager);

/**
 * Configure the manager.
 */
void external_radio_configure(
    ExternalRadioManager* manager,
    const ExternalRadioConfig* config);

/**
 * Start the manager (opens UART, begins detection).
 */
bool external_radio_start(ExternalRadioManager* manager);

/**
 * Stop the manager.
 */
void external_radio_stop(ExternalRadioManager* manager);

/**
 * Check if an external radio is connected.
 */
bool external_radio_is_connected(ExternalRadioManager* manager);

/**
 * Get the connected radio type.
 */
ExternalRadioType external_radio_get_type(ExternalRadioManager* manager);

/**
 * Get the radio state.
 */
ExternalRadioState external_radio_get_state(ExternalRadioManager* manager);

/**
 * Get radio info (call after connected).
 */
bool external_radio_get_info(ExternalRadioManager* manager, ExtRadioInfo* info);

/**
 * Get radio capabilities.
 */
uint32_t external_radio_get_capabilities(ExternalRadioManager* manager);

/**
 * Send a command to the external radio.
 */
bool external_radio_send_command(
    ExternalRadioManager* manager,
    ExtRadioCommand cmd,
    const uint8_t* payload,
    size_t payload_len);

/**
 * Send a command and wait for response.
 */
bool external_radio_send_command_sync(
    ExternalRadioManager* manager,
    ExtRadioCommand cmd,
    const uint8_t* payload,
    size_t payload_len,
    uint8_t* response,
    size_t* response_len,
    uint32_t timeout_ms);

/**
 * Get the radio type name.
 */
const char* external_radio_get_type_name(ExternalRadioType type);

/**
 * Calculate CRC8 for packet.
 */
uint8_t external_radio_calc_crc8(const uint8_t* data, size_t len);

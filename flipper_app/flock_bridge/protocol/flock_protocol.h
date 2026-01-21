#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// ============================================================================
// Protocol Version
// ============================================================================

#define FLOCK_PROTOCOL_VERSION 1

// ============================================================================
// Message Size Limits
// ============================================================================

// Maximum payload size we accept (limited by Flipper's RAM constraints)
// Must be less than UINT16_MAX (65535) since payload_length is uint16_t
#define FLOCK_MAX_PAYLOAD_SIZE 500

// Maximum total message size (header + payload)
#define FLOCK_MAX_MESSAGE_SIZE (4 + FLOCK_MAX_PAYLOAD_SIZE)

// Header size in bytes
#define FLOCK_HEADER_SIZE 4

// ============================================================================
// Message Types
// ============================================================================

typedef enum {
    FlockMsgTypeHeartbeat = 0x00,
    FlockMsgTypeWifiScanRequest = 0x01,
    FlockMsgTypeWifiScanResult = 0x02,
    FlockMsgTypeSubGhzScanRequest = 0x03,
    FlockMsgTypeSubGhzScanResult = 0x04,
    FlockMsgTypeStatusRequest = 0x05,
    FlockMsgTypeStatusResponse = 0x06,
    FlockMsgTypeWipsAlert = 0x07,
    FlockMsgTypeBleScanRequest = 0x08,
    FlockMsgTypeBleScanResult = 0x09,
    FlockMsgTypeIrScanRequest = 0x0A,
    FlockMsgTypeIrScanResult = 0x0B,
    FlockMsgTypeNfcScanRequest = 0x0C,
    FlockMsgTypeNfcScanResult = 0x0D,

    // Active Probe TX Commands - Public Safety & Fleet
    FlockMsgTypeLfProbeTx = 0x0E,        // Tire Kicker - 125kHz TPMS wake
    FlockMsgTypeIrStrobeTx = 0x0F,       // Opticom Verifier - Traffic preemption
    FlockMsgTypeWifiProbeTx = 0x10,      // Honey-Potter - Fleet SSID probing
    FlockMsgTypeBleActiveScan = 0x11,    // BlueForce Handshake - Force SCAN_RSP

    // Active Probe TX Commands - Infrastructure
    FlockMsgTypeZigbeeBeaconTx = 0x12,   // Zigbee Knocker - Mesh mapping
    FlockMsgTypeGpioPulseTx = 0x13,      // Ghost Car - Inductive loop spoof

    // Active Probe TX Commands - Physical Access
    FlockMsgTypeSubGhzReplayTx = 0x14,   // Sleep Denial - Alarm fatigue
    FlockMsgTypeWiegandReplayTx = 0x15,  // Replay Injector - Card bypass
    FlockMsgTypeMagSpoofTx = 0x16,       // MagSpoof - Magstripe emulation
    FlockMsgTypeIButtonEmulate = 0x17,   // Master Key - 1-Wire emulation

    // Active Probe TX Commands - Digital
    FlockMsgTypeNrf24InjectTx = 0x18,    // MouseJacker - Keystroke injection

    // Passive Scan Configuration
    FlockMsgTypeSubGhzConfig = 0x20,     // Configure Sub-GHz listener params
    FlockMsgTypeIrConfig = 0x21,         // Configure IR listener
    FlockMsgTypeNrf24Config = 0x22,      // Configure NRF24 scanner

    FlockMsgTypeError = 0xFF,
} FlockMessageType;

// ============================================================================
// Error Codes
// ============================================================================

#define FLOCK_ERR_NONE             0x00
#define FLOCK_ERR_INVALID_MSG      0x01
#define FLOCK_ERR_NOT_IMPLEMENTED  0x02
#define FLOCK_ERR_HARDWARE_FAIL    0x03
#define FLOCK_ERR_BUSY             0x04
#define FLOCK_ERR_TIMEOUT          0x05
#define FLOCK_ERR_INVALID_PARAM    0x06

// ============================================================================
// WiFi Security Types
// ============================================================================

typedef enum {
    WifiSecurityOpen = 0,
    WifiSecurityWEP = 1,
    WifiSecurityWPA = 2,
    WifiSecurityWPA2 = 3,
    WifiSecurityWPA3 = 4,
    WifiSecurityWPA2Enterprise = 5,
    WifiSecurityWPA3Enterprise = 6,
    WifiSecurityUnknown = 255,
} WifiSecurityType;

// ============================================================================
// Sub-GHz Modulation Types
// ============================================================================

typedef enum {
    ModulationAM = 0,
    ModulationFM = 1,
    ModulationASK = 2,
    ModulationFSK = 3,
    ModulationPSK = 4,
    ModulationOOK = 5,
    ModulationGFSK = 6,
    ModulationUnknown = 255,
} SubGhzModulation;

// ============================================================================
// WIPS Alert Types
// ============================================================================

typedef enum {
    WipsAlertEvilTwin = 0,
    WipsAlertDeauthAttack = 1,
    WipsAlertKarmaAttack = 2,
    WipsAlertHiddenNetworkStrong = 3,
    WipsAlertSuspiciousOpenNetwork = 4,
    WipsAlertWeakEncryption = 5,
    WipsAlertChannelInterference = 6,
    WipsAlertMacSpoofing = 7,
    WipsAlertRogueAp = 8,
    WipsAlertSignalAnomaly = 9,
    WipsAlertBeaconFlood = 10,
} WipsAlertType;

typedef enum {
    WipsSeverityCritical = 0,
    WipsSeverityHigh = 1,
    WipsSeverityMedium = 2,
    WipsSeverityLow = 3,
    WipsSeverityInfo = 4,
} WipsSeverity;

// ============================================================================
// Data Structures
// ============================================================================

// Message header (4 bytes)
typedef struct __attribute__((packed)) {
    uint8_t version;
    uint8_t type;
    uint16_t payload_length;
} FlockMessageHeader;

// WiFi network structure (43 bytes per entry)
typedef struct __attribute__((packed)) {
    char ssid[33];           // 32 chars + null
    uint8_t bssid[6];        // MAC address
    int8_t rssi;             // Signal strength dBm
    uint8_t channel;         // WiFi channel (1-14)
    uint8_t security;        // WifiSecurityType
    uint8_t hidden;          // 0 = visible, 1 = hidden
} FlockWifiNetwork;

// Sub-GHz detection structure (29 bytes per entry)
typedef struct __attribute__((packed)) {
    uint32_t frequency;      // Frequency in Hz
    int8_t rssi;             // Signal strength dBm
    uint8_t modulation;      // SubGhzModulation
    uint16_t duration_ms;    // Signal duration
    uint32_t bandwidth;      // Bandwidth in Hz
    uint8_t protocol_id;     // Known protocol ID (0 = unknown)
    char protocol_name[16];  // Protocol name if known
} FlockSubGhzDetection;

// WIPS alert structure
typedef struct __attribute__((packed)) {
    uint32_t timestamp;      // Unix timestamp
    uint8_t alert_type;      // WipsAlertType
    uint8_t severity;        // WipsSeverity
    char ssid[33];           // Affected SSID
    uint8_t bssid_count;     // Number of BSSIDs involved
    uint8_t bssids[4][6];    // Up to 4 BSSIDs (6 bytes each)
    char description[64];    // Human-readable description
} FlockWipsAlert;

// WiFi scan result message
#define MAX_WIFI_NETWORKS 32
typedef struct {
    uint32_t timestamp;
    uint8_t network_count;
    FlockWifiNetwork networks[MAX_WIFI_NETWORKS];
} FlockWifiScanResult;

// Sub-GHz scan result message
#define MAX_SUBGHZ_DETECTIONS 16
typedef struct {
    uint32_t timestamp;
    uint32_t frequency_start;
    uint32_t frequency_end;
    uint8_t detection_count;
    FlockSubGhzDetection detections[MAX_SUBGHZ_DETECTIONS];
} FlockSubGhzScanResult;

// BLE device detection structure
typedef struct __attribute__((packed)) {
    uint8_t mac_address[6];    // MAC address
    char name[32];             // Device name (31 chars + null)
    int8_t rssi;               // Signal strength dBm
    uint8_t address_type;      // 0=public, 1=random
    uint8_t is_connectable;    // 0=no, 1=yes
    uint8_t service_uuid_count;
    uint8_t service_uuids[4][16]; // Up to 4 128-bit UUIDs
    uint8_t manufacturer_id[2];   // Manufacturer ID
    uint8_t manufacturer_data_len;
    uint8_t manufacturer_data[32];
} FlockBleDevice;

// BLE scan result
#define MAX_BLE_DEVICES 32
typedef struct {
    uint32_t timestamp;
    uint8_t device_count;
    FlockBleDevice devices[MAX_BLE_DEVICES];
} FlockBleScanResult;

// IR detection structure
typedef struct __attribute__((packed)) {
    uint32_t timestamp;
    uint8_t protocol_id;       // NEC, Samsung, Sony, RC5, RC6, etc.
    char protocol_name[16];    // Protocol name
    uint32_t address;          // Device address
    uint32_t command;          // Command code
    uint8_t repeat;            // Is repeat signal
    int8_t signal_strength;    // Relative signal strength
} FlockIrDetection;

// IR scan result
#define MAX_IR_DETECTIONS 16
typedef struct {
    uint32_t timestamp;
    uint8_t detection_count;
    FlockIrDetection detections[MAX_IR_DETECTIONS];
} FlockIrScanResult;

// NFC detection structure
typedef struct __attribute__((packed)) {
    uint8_t uid[10];           // UID (up to 10 bytes)
    uint8_t uid_len;           // Actual UID length (4, 7, or 10)
    uint8_t type;              // NFC type (A, B, F, V)
    uint8_t sak;               // SAK byte (for Type A)
    uint8_t atqa[2];           // ATQA bytes (for Type A)
    char type_name[16];        // Human-readable type
} FlockNfcDetection;

// NFC scan result
#define MAX_NFC_DETECTIONS 8
typedef struct {
    uint32_t timestamp;
    uint8_t detection_count;
    FlockNfcDetection detections[MAX_NFC_DETECTIONS];
} FlockNfcScanResult;

// Status response
typedef struct __attribute__((packed)) {
    uint8_t protocol_version;
    uint8_t wifi_board_connected;
    uint8_t subghz_ready;
    uint8_t ble_ready;
    uint8_t ir_ready;
    uint8_t nfc_ready;
    uint8_t battery_percent;
    uint32_t uptime_seconds;
    uint16_t wifi_scan_count;
    uint16_t subghz_detection_count;
    uint16_t ble_scan_count;
    uint16_t ir_detection_count;
    uint16_t nfc_detection_count;
    uint16_t wips_alert_count;
} FlockStatusResponse;

// ============================================================================
// Active Probe Payload Structures
// ============================================================================

// LF Probe (Tire Kicker) - 125kHz TPMS wake
typedef struct __attribute__((packed)) {
    uint16_t duration_ms;    // Duration to hold 125kHz carrier (100-5000ms)
} FlockLfProbePayload;

// IR Strobe (Opticom Verifier) - Traffic preemption test
typedef struct __attribute__((packed)) {
    uint16_t frequency_hz;   // Strobe frequency (14=High Prio, 10=Low Prio)
    uint8_t duty_cycle;      // PWM duty cycle 0-100
    uint16_t duration_ms;    // How long to strobe (100-10000ms)
} FlockIrStrobePayload;

// Wi-Fi Probe (Honey-Potter) - Fleet SSID probing
typedef struct __attribute__((packed)) {
    uint8_t ssid_len;        // Length of target SSID (1-32)
    char ssid[32];           // Target SSID (not null terminated, use ssid_len)
} FlockWifiProbePayload;

// BLE Active Scan (BlueForce Handshake) - Force SCAN_RSP
typedef struct __attribute__((packed)) {
    uint8_t active_mode;     // 1=active (send SCAN_REQ), 0=passive
} FlockBleActiveScanPayload;

// Zigbee Beacon (Zigbee Knocker) - Mesh mapping
typedef struct __attribute__((packed)) {
    uint8_t channel;         // Zigbee channel 11-26, 0=hop
} FlockZigbeeBeaconPayload;

// GPIO Pulse (Ghost Car) - Inductive loop spoof
typedef struct __attribute__((packed)) {
    uint32_t frequency_hz;   // Resonant frequency (20000-150000 Hz typical)
    uint16_t duration_ms;    // Pulse duration (50-5000ms)
    uint16_t pulse_count;    // Number of pulses (1-20)
} FlockGpioPulsePayload;

// Sub-GHz Replay (Sleep Denial) - Alarm fatigue
#define MAX_REPLAY_DATA_SIZE 256
typedef struct __attribute__((packed)) {
    uint32_t frequency;      // Target frequency in Hz
    uint16_t data_len;       // Length of raw signal data
    uint8_t repeat_count;    // Number of replays (1-100)
    uint8_t data[MAX_REPLAY_DATA_SIZE]; // Captured signal data
} FlockSubGhzReplayPayload;

// Wiegand Replay (Replay Injector) - Card bypass
typedef struct __attribute__((packed)) {
    uint32_t facility_code;  // Facility code from captured card
    uint32_t card_number;    // Card number from captured card
    uint8_t bit_length;      // Wiegand format (26, 34, 37, etc.)
} FlockWiegandReplayPayload;

// MagSpoof - Magstripe emulation
typedef struct __attribute__((packed)) {
    uint8_t track1_len;      // Length of track 1 data (0-79)
    char track1[80];         // Track 1 alphanumeric data
    uint8_t track2_len;      // Length of track 2 data (0-40)
    char track2[41];         // Track 2 numeric data
} FlockMagSpoofPayload;

// iButton Emulate (Master Key) - 1-Wire emulation
typedef struct __attribute__((packed)) {
    uint8_t key_id[8];       // DS1990A 8-byte key ID
} FlockIButtonPayload;

// NRF24 Inject (MouseJacker) - Keystroke injection
#define MAX_KEYSTROKE_SIZE 64
typedef struct __attribute__((packed)) {
    uint8_t address[5];      // NRF24 5-byte address
    uint8_t keystroke_len;   // Number of keycodes
    uint8_t keystrokes[MAX_KEYSTROKE_SIZE]; // HID keycodes
} FlockNrf24InjectPayload;

// Sub-GHz Configuration - Passive scan params
typedef struct __attribute__((packed)) {
    uint8_t probe_type;      // 0=TPMS, 1=P25, 2=LoJack, 3=Pager, 4=PowerGrid, 5=Crane, 6=ESL, 7=Thermal
    uint32_t frequency;      // Target frequency (0=default for probe type)
    uint8_t modulation;      // 0=ASK, 1=FSK, 2=GFSK
} FlockSubGhzConfigPayload;

// IR Configuration - Passive detection params
typedef struct __attribute__((packed)) {
    uint8_t detect_opticom;  // 1=detect 14/10Hz emergency strobes
} FlockIrConfigPayload;

// NRF24 Configuration - Promiscuous scan params
typedef struct __attribute__((packed)) {
    uint8_t promiscuous;     // 1=scan all channels for vulnerable devices
} FlockNrf24ConfigPayload;

// ============================================================================
// Function Prototypes - Serialization
// ============================================================================

/**
 * Serialize a WiFi scan result into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_wifi_result(
    const FlockWifiScanResult* result,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Serialize a Sub-GHz scan result into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_subghz_result(
    const FlockSubGhzScanResult* result,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Serialize a status response into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_status(
    const FlockStatusResponse* status,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Serialize a WIPS alert into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_wips_alert(
    const FlockWipsAlert* alert,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Serialize a BLE scan result into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_ble_result(
    const FlockBleScanResult* result,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Serialize an IR scan result into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_ir_result(
    const FlockIrScanResult* result,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Serialize an NFC scan result into a buffer.
 * Returns the number of bytes written, or 0 on error.
 */
size_t flock_protocol_serialize_nfc_result(
    const FlockNfcScanResult* result,
    uint8_t* buffer,
    size_t buffer_size);

/**
 * Create a heartbeat response.
 * Returns the number of bytes written.
 */
size_t flock_protocol_create_heartbeat(uint8_t* buffer, size_t buffer_size);

/**
 * Create an error response.
 * Returns the number of bytes written.
 */
size_t flock_protocol_create_error(
    uint8_t error_code,
    const char* message,
    uint8_t* buffer,
    size_t buffer_size);

// ============================================================================
// Function Prototypes - Parsing
// ============================================================================

/**
 * Parse a message header from buffer.
 * Returns true if successful, false if buffer too small or invalid.
 */
bool flock_protocol_parse_header(
    const uint8_t* buffer,
    size_t length,
    FlockMessageHeader* header);

/**
 * Get the message type from a received buffer.
 * Returns the message type, or 0xFF on error.
 */
FlockMessageType flock_protocol_get_message_type(
    const uint8_t* buffer,
    size_t length);

/**
 * Parse a WiFi scan request.
 * Currently no payload, just validates header.
 */
bool flock_protocol_parse_wifi_scan_request(
    const uint8_t* buffer,
    size_t length);

/**
 * Parse a Sub-GHz scan request.
 * Extracts frequency range if provided.
 */
bool flock_protocol_parse_subghz_scan_request(
    const uint8_t* buffer,
    size_t length,
    uint32_t* frequency_start,
    uint32_t* frequency_end);

// ============================================================================
// Active Probe Parsing Functions
// ============================================================================

/**
 * Parse LF probe (Tire Kicker) request.
 * Returns true if valid, false otherwise.
 */
bool flock_protocol_parse_lf_probe(
    const uint8_t* buffer,
    size_t length,
    FlockLfProbePayload* payload);

/**
 * Parse IR strobe (Opticom Verifier) request.
 */
bool flock_protocol_parse_ir_strobe(
    const uint8_t* buffer,
    size_t length,
    FlockIrStrobePayload* payload);

/**
 * Parse Wi-Fi probe (Honey-Potter) request.
 */
bool flock_protocol_parse_wifi_probe(
    const uint8_t* buffer,
    size_t length,
    FlockWifiProbePayload* payload);

/**
 * Parse BLE active scan (BlueForce Handshake) request.
 */
bool flock_protocol_parse_ble_active_scan(
    const uint8_t* buffer,
    size_t length,
    FlockBleActiveScanPayload* payload);

/**
 * Parse GPIO pulse (Ghost Car) request.
 */
bool flock_protocol_parse_gpio_pulse(
    const uint8_t* buffer,
    size_t length,
    FlockGpioPulsePayload* payload);

/**
 * Parse Zigbee beacon (Zigbee Knocker) request.
 */
bool flock_protocol_parse_zigbee_beacon(
    const uint8_t* buffer,
    size_t length,
    FlockZigbeeBeaconPayload* payload);

/**
 * Parse Sub-GHz replay (Sleep Denial) request.
 */
bool flock_protocol_parse_subghz_replay(
    const uint8_t* buffer,
    size_t length,
    FlockSubGhzReplayPayload* payload);

/**
 * Parse Wiegand replay (Replay Injector) request.
 */
bool flock_protocol_parse_wiegand_replay(
    const uint8_t* buffer,
    size_t length,
    FlockWiegandReplayPayload* payload);

/**
 * Parse MagSpoof request.
 */
bool flock_protocol_parse_magspoof(
    const uint8_t* buffer,
    size_t length,
    FlockMagSpoofPayload* payload);

/**
 * Parse iButton emulate (Master Key) request.
 */
bool flock_protocol_parse_ibutton(
    const uint8_t* buffer,
    size_t length,
    FlockIButtonPayload* payload);

/**
 * Parse NRF24 inject (MouseJacker) request.
 */
bool flock_protocol_parse_nrf24_inject(
    const uint8_t* buffer,
    size_t length,
    FlockNrf24InjectPayload* payload);

/**
 * Parse Sub-GHz configuration request.
 */
bool flock_protocol_parse_subghz_config(
    const uint8_t* buffer,
    size_t length,
    FlockSubGhzConfigPayload* payload);

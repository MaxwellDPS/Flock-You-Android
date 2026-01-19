#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// ============================================================================
// Protocol Version
// ============================================================================

#define FLOCK_PROTOCOL_VERSION 1

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
    FlockMsgTypeError = 0xFF,
} FlockMessageType;

// ============================================================================
// WiFi Security Types
// ============================================================================

typedef enum {
    WifiSecurityOpen = 0,
    WifiSecurityWEP = 1,
    WifiSecurityWPA = 2,
    WifiSecurityWPA2 = 3,
    WifiSecurityWPA3 = 4,
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

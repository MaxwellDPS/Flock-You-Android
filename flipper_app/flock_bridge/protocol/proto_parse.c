/**
 * Protocol Parsing - Header, Scan Requests, Wireless Probes, and Configuration
 *
 * Parses incoming messages and commands from wire format.
 * Hardware probe parsing (LF, IR, GPIO, etc.) is in proto_parse_probes.c
 */

#include "flock_protocol.h"
#include <string.h>

#define HEADER_SIZE FLOCK_HEADER_SIZE

// ============================================================================
// Header Parsing
// ============================================================================

bool flock_protocol_parse_header(
    const uint8_t* buffer,
    size_t length,
    FlockMessageHeader* header) {

    if (!buffer || !header || length < HEADER_SIZE) return false;

    header->version = buffer[0];
    header->type = buffer[1];
    header->payload_length = (uint16_t)buffer[2] | ((uint16_t)buffer[3] << 8);

    return header->version == FLOCK_PROTOCOL_VERSION;
}

FlockMessageType flock_protocol_get_message_type(
    const uint8_t* buffer,
    size_t length) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return FlockMsgTypeError;
    }
    return (FlockMessageType)header.type;
}

// ============================================================================
// Scan Request Parsing
// ============================================================================

bool flock_protocol_parse_wifi_scan_request(
    const uint8_t* buffer,
    size_t length) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }
    return header.type == FlockMsgTypeWifiScanRequest;
}

bool flock_protocol_parse_subghz_scan_request(
    const uint8_t* buffer,
    size_t length,
    uint32_t* frequency_start,
    uint32_t* frequency_end) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeSubGhzScanRequest) {
        return false;
    }

    if (header.payload_length >= 8 && length >= HEADER_SIZE + 8) {
        const uint8_t* p = buffer + HEADER_SIZE;

        if (frequency_start) {
            *frequency_start = (uint32_t)p[0] |
                              ((uint32_t)p[1] << 8) |
                              ((uint32_t)p[2] << 16) |
                              ((uint32_t)p[3] << 24);
        }

        if (frequency_end) {
            *frequency_end = (uint32_t)p[4] |
                            ((uint32_t)p[5] << 8) |
                            ((uint32_t)p[6] << 16) |
                            ((uint32_t)p[7] << 24);
        }
    } else {
        if (frequency_start) *frequency_start = 300000000;
        if (frequency_end) *frequency_end = 928000000;
    }

    return true;
}

// ============================================================================
// Wireless Probe Parsing - WiFi, BLE
// ============================================================================

bool flock_protocol_parse_wifi_probe(
    const uint8_t* buffer,
    size_t length,
    FlockWifiProbePayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeWifiProbeTx || header.payload_length < 1) {
        return false;
    }

    if (length < HEADER_SIZE + 1) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->ssid_len = p[0];

    if (payload->ssid_len > 32) {
        payload->ssid_len = 32;
    }

    if (length < (size_t)(HEADER_SIZE + 1 + payload->ssid_len)) {
        return false;
    }

    memset(payload->ssid, 0, sizeof(payload->ssid));
    memcpy(payload->ssid, p + 1, payload->ssid_len);

    return true;
}

bool flock_protocol_parse_ble_active_scan(
    const uint8_t* buffer,
    size_t length,
    FlockBleActiveScanPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeBleActiveScan || header.payload_length < 1) {
        return false;
    }

    if (length < HEADER_SIZE + 1) {
        return false;
    }

    payload->active_mode = buffer[HEADER_SIZE];
    return true;
}

// ============================================================================
// Configuration Parsing
// ============================================================================

bool flock_protocol_parse_subghz_config(
    const uint8_t* buffer,
    size_t length,
    FlockSubGhzConfigPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeSubGhzConfig || header.payload_length < 6) {
        return false;
    }

    if (length < HEADER_SIZE + 6) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->probe_type = p[0];
    payload->frequency = (uint32_t)p[1] | ((uint32_t)p[2] << 8) |
                        ((uint32_t)p[3] << 16) | ((uint32_t)p[4] << 24);
    payload->modulation = p[5];

    return true;
}

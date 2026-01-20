#include "flock_protocol.h"
#include <string.h>

#define HEADER_SIZE 4

// ============================================================================
// Helper Functions
// ============================================================================

static void write_header(uint8_t* buffer, uint8_t type, uint16_t payload_length) {
    buffer[0] = FLOCK_PROTOCOL_VERSION;
    buffer[1] = type;
    buffer[2] = (uint8_t)(payload_length & 0xFF);
    buffer[3] = (uint8_t)((payload_length >> 8) & 0xFF);
}

// ============================================================================
// Serialization Functions
// ============================================================================

size_t flock_protocol_serialize_wifi_result(
    const FlockWifiScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    // Calculate payload size - clamp network_count for size calculation
    // timestamp (4) + count (1) + networks (43 * count)
    uint8_t count_for_size = result->network_count;
    if (count_for_size > MAX_WIFI_NETWORKS) {
        count_for_size = MAX_WIFI_NETWORKS;
    }
    size_t payload_size = 5 + (count_for_size * sizeof(FlockWifiNetwork));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    // Write header
    write_header(buffer, FlockMsgTypeWifiScanResult, payload_size);

    // Write payload
    uint8_t* p = buffer + HEADER_SIZE;

    // Timestamp (little-endian)
    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    // Network count - clamp to MAX_WIFI_NETWORKS to prevent buffer overflow
    uint8_t safe_count = result->network_count;
    if (safe_count > MAX_WIFI_NETWORKS) {
        safe_count = MAX_WIFI_NETWORKS;
    }
    *p++ = safe_count;

    // Networks
    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->networks[i], sizeof(FlockWifiNetwork));
        p += sizeof(FlockWifiNetwork);
    }

    return total_size;
}

size_t flock_protocol_serialize_subghz_result(
    const FlockSubGhzScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    // Calculate payload size - clamp detection_count for size calculation
    // timestamp (4) + freq_start (4) + freq_end (4) + count (1) + detections (29 * count)
    uint8_t count_for_size = result->detection_count;
    if (count_for_size > MAX_SUBGHZ_DETECTIONS) {
        count_for_size = MAX_SUBGHZ_DETECTIONS;
    }
    size_t payload_size = 13 + (count_for_size * sizeof(FlockSubGhzDetection));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    // Write header
    write_header(buffer, FlockMsgTypeSubGhzScanResult, payload_size);

    // Write payload
    uint8_t* p = buffer + HEADER_SIZE;

    // Timestamp
    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    // Frequency start
    *p++ = (uint8_t)(result->frequency_start & 0xFF);
    *p++ = (uint8_t)((result->frequency_start >> 8) & 0xFF);
    *p++ = (uint8_t)((result->frequency_start >> 16) & 0xFF);
    *p++ = (uint8_t)((result->frequency_start >> 24) & 0xFF);

    // Frequency end
    *p++ = (uint8_t)(result->frequency_end & 0xFF);
    *p++ = (uint8_t)((result->frequency_end >> 8) & 0xFF);
    *p++ = (uint8_t)((result->frequency_end >> 16) & 0xFF);
    *p++ = (uint8_t)((result->frequency_end >> 24) & 0xFF);

    // Detection count - clamp to MAX_SUBGHZ_DETECTIONS to prevent buffer overflow
    uint8_t safe_count = result->detection_count;
    if (safe_count > MAX_SUBGHZ_DETECTIONS) {
        safe_count = MAX_SUBGHZ_DETECTIONS;
    }
    *p++ = safe_count;

    // Detections
    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->detections[i], sizeof(FlockSubGhzDetection));
        p += sizeof(FlockSubGhzDetection);
    }

    return total_size;
}

size_t flock_protocol_serialize_status(
    const FlockStatusResponse* status,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!status || !buffer) return 0;

    size_t payload_size = sizeof(FlockStatusResponse);
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    // Write header
    write_header(buffer, FlockMsgTypeStatusResponse, payload_size);

    // Write payload
    memcpy(buffer + HEADER_SIZE, status, payload_size);

    return total_size;
}

size_t flock_protocol_serialize_wips_alert(
    const FlockWipsAlert* alert,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!alert || !buffer) return 0;

    size_t payload_size = sizeof(FlockWipsAlert);
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    // Write header
    write_header(buffer, FlockMsgTypeWipsAlert, payload_size);

    // Write payload
    memcpy(buffer + HEADER_SIZE, alert, payload_size);

    return total_size;
}

size_t flock_protocol_create_heartbeat(uint8_t* buffer, size_t buffer_size) {
    if (!buffer || buffer_size < HEADER_SIZE) return 0;

    write_header(buffer, FlockMsgTypeHeartbeat, 0);
    return HEADER_SIZE;
}

size_t flock_protocol_create_error(
    uint8_t error_code,
    const char* message,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!buffer) return 0;

    size_t msg_len = message ? strlen(message) : 0;
    if (msg_len > 64) msg_len = 64;

    size_t payload_size = 1 + msg_len;
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeError, payload_size);

    buffer[HEADER_SIZE] = error_code;
    if (msg_len > 0) {
        memcpy(buffer + HEADER_SIZE + 1, message, msg_len);
    }

    return total_size;
}

// ============================================================================
// Parsing Functions
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

    // Check if frequency range is provided
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
        // Use default range
        if (frequency_start) *frequency_start = 300000000;
        if (frequency_end) *frequency_end = 928000000;
    }

    return true;
}

// ============================================================================
// Active Probe Parsing Functions
// ============================================================================

bool flock_protocol_parse_lf_probe(
    const uint8_t* buffer,
    size_t length,
    FlockLfProbePayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeLfProbeTx || header.payload_length < 2) {
        return false;
    }

    if (length < HEADER_SIZE + 2) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->duration_ms = (uint16_t)p[0] | ((uint16_t)p[1] << 8);

    // Enforce safety cap: max 5 seconds to protect battery
    if (payload->duration_ms > 5000) {
        payload->duration_ms = 5000;
    }
    if (payload->duration_ms < 100) {
        payload->duration_ms = 100;
    }

    return true;
}

bool flock_protocol_parse_ir_strobe(
    const uint8_t* buffer,
    size_t length,
    FlockIrStrobePayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeIrStrobeTx || header.payload_length < 5) {
        return false;
    }

    if (length < HEADER_SIZE + 5) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->frequency_hz = (uint16_t)p[0] | ((uint16_t)p[1] << 8);
    payload->duty_cycle = p[2];
    payload->duration_ms = (uint16_t)p[3] | ((uint16_t)p[4] << 8);

    // Enforce safety limits
    if (payload->duty_cycle > 100) payload->duty_cycle = 50;
    if (payload->duration_ms > 10000) payload->duration_ms = 10000;
    if (payload->duration_ms < 100) payload->duration_ms = 100;

    return true;
}

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

bool flock_protocol_parse_gpio_pulse(
    const uint8_t* buffer,
    size_t length,
    FlockGpioPulsePayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeGpioPulseTx || header.payload_length < 8) {
        return false;
    }

    if (length < HEADER_SIZE + 8) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->frequency_hz = (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    payload->duration_ms = (uint16_t)p[4] | ((uint16_t)p[5] << 8);
    payload->pulse_count = (uint16_t)p[6] | ((uint16_t)p[7] << 8);

    // Safety limits
    if (payload->duration_ms > 5000) payload->duration_ms = 5000;
    if (payload->pulse_count > 20) payload->pulse_count = 20;

    return true;
}

bool flock_protocol_parse_subghz_replay(
    const uint8_t* buffer,
    size_t length,
    FlockSubGhzReplayPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeSubGhzReplayTx || header.payload_length < 7) {
        return false;
    }

    if (length < HEADER_SIZE + 7) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->frequency = (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                        ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    payload->data_len = (uint16_t)p[4] | ((uint16_t)p[5] << 8);
    payload->repeat_count = p[6];

    if (payload->data_len > MAX_REPLAY_DATA_SIZE) {
        payload->data_len = MAX_REPLAY_DATA_SIZE;
    }

    if (payload->repeat_count > 100) {
        payload->repeat_count = 100;
    }

    if (length < (size_t)(HEADER_SIZE + 7 + payload->data_len)) {
        return false;
    }

    memcpy(payload->data, p + 7, payload->data_len);
    return true;
}

bool flock_protocol_parse_wiegand_replay(
    const uint8_t* buffer,
    size_t length,
    FlockWiegandReplayPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeWiegandReplayTx || header.payload_length < 9) {
        return false;
    }

    if (length < HEADER_SIZE + 9) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->facility_code = (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                            ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    payload->card_number = (uint32_t)p[4] | ((uint32_t)p[5] << 8) |
                          ((uint32_t)p[6] << 16) | ((uint32_t)p[7] << 24);
    payload->bit_length = p[8];

    // Validate bit length
    if (payload->bit_length < 26) payload->bit_length = 26;
    if (payload->bit_length > 48) payload->bit_length = 48;

    return true;
}

bool flock_protocol_parse_magspoof(
    const uint8_t* buffer,
    size_t length,
    FlockMagSpoofPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeMagSpoofTx || header.payload_length < 2) {
        return false;
    }

    if (length < HEADER_SIZE + 2) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    payload->track1_len = p[0];
    if (payload->track1_len > 79) payload->track1_len = 79;

    size_t offset = 1;
    if (length < HEADER_SIZE + offset + payload->track1_len + 1) {
        return false;
    }

    memset(payload->track1, 0, sizeof(payload->track1));
    memcpy(payload->track1, p + offset, payload->track1_len);
    offset += payload->track1_len;

    payload->track2_len = p[offset];
    if (payload->track2_len > 40) payload->track2_len = 40;
    offset++;

    if (length < HEADER_SIZE + offset + payload->track2_len) {
        return false;
    }

    memset(payload->track2, 0, sizeof(payload->track2));
    memcpy(payload->track2, p + offset, payload->track2_len);

    return true;
}

bool flock_protocol_parse_ibutton(
    const uint8_t* buffer,
    size_t length,
    FlockIButtonPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeIButtonEmulate || header.payload_length < 8) {
        return false;
    }

    if (length < HEADER_SIZE + 8) {
        return false;
    }

    memcpy(payload->key_id, buffer + HEADER_SIZE, 8);
    return true;
}

bool flock_protocol_parse_nrf24_inject(
    const uint8_t* buffer,
    size_t length,
    FlockNrf24InjectPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeNrf24InjectTx || header.payload_length < 6) {
        return false;
    }

    if (length < HEADER_SIZE + 6) {
        return false;
    }

    const uint8_t* p = buffer + HEADER_SIZE;
    memcpy(payload->address, p, 5);
    payload->keystroke_len = p[5];

    if (payload->keystroke_len > MAX_KEYSTROKE_SIZE) {
        payload->keystroke_len = MAX_KEYSTROKE_SIZE;
    }

    if (length < (size_t)(HEADER_SIZE + 6 + payload->keystroke_len)) {
        return false;
    }

    memcpy(payload->keystrokes, p + 6, payload->keystroke_len);
    return true;
}

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

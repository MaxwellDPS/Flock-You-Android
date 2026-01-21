/**
 * Protocol Parsing - Hardware Active Probe Commands
 *
 * Parses hardware probe TX commands from wire format.
 * Includes: LF, IR, Zigbee, GPIO, SubGHz Replay, Wiegand, MagSpoof, iButton, NRF24
 *
 * Wireless probes (WiFi, BLE) are in proto_parse.c
 */

#include "flock_protocol.h"
#include <string.h>

#define HEADER_SIZE FLOCK_HEADER_SIZE

// ============================================================================
// Active Probe Parsing - LF, IR
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

    if (payload->duty_cycle > 100) payload->duty_cycle = 50;
    if (payload->duration_ms > 10000) payload->duration_ms = 10000;
    if (payload->duration_ms < 100) payload->duration_ms = 100;

    return true;
}

// ============================================================================
// Active Probe Parsing - Zigbee
// ============================================================================

bool flock_protocol_parse_zigbee_beacon(
    const uint8_t* buffer,
    size_t length,
    FlockZigbeeBeaconPayload* payload) {

    FlockMessageHeader header;
    if (!flock_protocol_parse_header(buffer, length, &header)) {
        return false;
    }

    if (header.type != FlockMsgTypeZigbeeBeaconTx || header.payload_length < 1) {
        return false;
    }

    if (length < HEADER_SIZE + 1) {
        return false;
    }

    payload->channel = buffer[HEADER_SIZE];

    // Validate channel: 0 = hop, or 11-26 for specific Zigbee channels
    if (payload->channel != 0 && (payload->channel < 11 || payload->channel > 26)) {
        payload->channel = 0; // Default to channel hopping if invalid
    }

    return true;
}

// ============================================================================
// Active Probe Parsing - GPIO, SubGHz, Wiegand
// ============================================================================

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

    if (payload->bit_length < 26) payload->bit_length = 26;
    if (payload->bit_length > 48) payload->bit_length = 48;

    return true;
}

// ============================================================================
// Active Probe Parsing - MagSpoof, iButton, NRF24
// ============================================================================

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

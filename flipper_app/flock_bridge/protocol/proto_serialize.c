/**
 * Protocol Serialization
 *
 * Serializes scan results and responses into wire format.
 */

#include "flock_protocol.h"
#include <string.h>

#define HEADER_SIZE FLOCK_HEADER_SIZE

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
// WiFi Serialization
// ============================================================================

size_t flock_protocol_serialize_wifi_result(
    const FlockWifiScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    uint8_t count_for_size = result->network_count;
    if (count_for_size > MAX_WIFI_NETWORKS) {
        count_for_size = MAX_WIFI_NETWORKS;
    }
    size_t payload_size = 5 + (count_for_size * sizeof(FlockWifiNetwork));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeWifiScanResult, payload_size);

    uint8_t* p = buffer + HEADER_SIZE;

    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    uint8_t safe_count = result->network_count;
    if (safe_count > MAX_WIFI_NETWORKS) {
        safe_count = MAX_WIFI_NETWORKS;
    }
    *p++ = safe_count;

    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->networks[i], sizeof(FlockWifiNetwork));
        p += sizeof(FlockWifiNetwork);
    }

    return total_size;
}

// ============================================================================
// Sub-GHz Serialization
// ============================================================================

size_t flock_protocol_serialize_subghz_result(
    const FlockSubGhzScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    uint8_t count_for_size = result->detection_count;
    if (count_for_size > MAX_SUBGHZ_DETECTIONS) {
        count_for_size = MAX_SUBGHZ_DETECTIONS;
    }
    size_t payload_size = 13 + (count_for_size * sizeof(FlockSubGhzDetection));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeSubGhzScanResult, payload_size);

    uint8_t* p = buffer + HEADER_SIZE;

    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    *p++ = (uint8_t)(result->frequency_start & 0xFF);
    *p++ = (uint8_t)((result->frequency_start >> 8) & 0xFF);
    *p++ = (uint8_t)((result->frequency_start >> 16) & 0xFF);
    *p++ = (uint8_t)((result->frequency_start >> 24) & 0xFF);

    *p++ = (uint8_t)(result->frequency_end & 0xFF);
    *p++ = (uint8_t)((result->frequency_end >> 8) & 0xFF);
    *p++ = (uint8_t)((result->frequency_end >> 16) & 0xFF);
    *p++ = (uint8_t)((result->frequency_end >> 24) & 0xFF);

    uint8_t safe_count = result->detection_count;
    if (safe_count > MAX_SUBGHZ_DETECTIONS) {
        safe_count = MAX_SUBGHZ_DETECTIONS;
    }
    *p++ = safe_count;

    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->detections[i], sizeof(FlockSubGhzDetection));
        p += sizeof(FlockSubGhzDetection);
    }

    return total_size;
}

// ============================================================================
// Sub-GHz Scan Status Serialization
// ============================================================================

size_t flock_protocol_serialize_subghz_status(
    const FlockSubGhzScanStatus* status,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!status || !buffer) return 0;

    size_t payload_size = sizeof(FlockSubGhzScanStatus);
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeSubGhzScanStatus, payload_size);
    memcpy(buffer + HEADER_SIZE, status, payload_size);

    return total_size;
}

// ============================================================================
// Status Serialization
// ============================================================================

size_t flock_protocol_serialize_status(
    const FlockStatusResponse* status,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!status || !buffer) return 0;

    size_t payload_size = sizeof(FlockStatusResponse);
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeStatusResponse, payload_size);
    memcpy(buffer + HEADER_SIZE, status, payload_size);

    return total_size;
}

// ============================================================================
// WIPS Alert Serialization
// ============================================================================

size_t flock_protocol_serialize_wips_alert(
    const FlockWipsAlert* alert,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!alert || !buffer) return 0;

    size_t payload_size = sizeof(FlockWipsAlert);
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeWipsAlert, payload_size);
    memcpy(buffer + HEADER_SIZE, alert, payload_size);

    return total_size;
}

// ============================================================================
// BLE Serialization
// ============================================================================

size_t flock_protocol_serialize_ble_result(
    const FlockBleScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    uint8_t count_for_size = result->device_count;
    if (count_for_size > MAX_BLE_DEVICES) {
        count_for_size = MAX_BLE_DEVICES;
    }
    size_t payload_size = 5 + (count_for_size * sizeof(FlockBleDevice));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeBleScanResult, payload_size);

    uint8_t* p = buffer + HEADER_SIZE;

    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    uint8_t safe_count = result->device_count;
    if (safe_count > MAX_BLE_DEVICES) {
        safe_count = MAX_BLE_DEVICES;
    }
    *p++ = safe_count;

    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->devices[i], sizeof(FlockBleDevice));
        p += sizeof(FlockBleDevice);
    }

    return total_size;
}

// ============================================================================
// IR Serialization
// ============================================================================

size_t flock_protocol_serialize_ir_result(
    const FlockIrScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    uint8_t count_for_size = result->detection_count;
    if (count_for_size > MAX_IR_DETECTIONS) {
        count_for_size = MAX_IR_DETECTIONS;
    }
    size_t payload_size = 5 + (count_for_size * sizeof(FlockIrDetection));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeIrScanResult, payload_size);

    uint8_t* p = buffer + HEADER_SIZE;

    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    uint8_t safe_count = result->detection_count;
    if (safe_count > MAX_IR_DETECTIONS) {
        safe_count = MAX_IR_DETECTIONS;
    }
    *p++ = safe_count;

    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->detections[i], sizeof(FlockIrDetection));
        p += sizeof(FlockIrDetection);
    }

    return total_size;
}

// ============================================================================
// NFC Serialization
// ============================================================================

size_t flock_protocol_serialize_nfc_result(
    const FlockNfcScanResult* result,
    uint8_t* buffer,
    size_t buffer_size) {

    if (!result || !buffer) return 0;

    uint8_t count_for_size = result->detection_count;
    if (count_for_size > MAX_NFC_DETECTIONS) {
        count_for_size = MAX_NFC_DETECTIONS;
    }
    size_t payload_size = 5 + (count_for_size * sizeof(FlockNfcDetection));
    size_t total_size = HEADER_SIZE + payload_size;

    if (buffer_size < total_size) return 0;

    write_header(buffer, FlockMsgTypeNfcScanResult, payload_size);

    uint8_t* p = buffer + HEADER_SIZE;

    *p++ = (uint8_t)(result->timestamp & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 8) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 16) & 0xFF);
    *p++ = (uint8_t)((result->timestamp >> 24) & 0xFF);

    uint8_t safe_count = result->detection_count;
    if (safe_count > MAX_NFC_DETECTIONS) {
        safe_count = MAX_NFC_DETECTIONS;
    }
    *p++ = safe_count;

    for (uint8_t i = 0; i < safe_count; i++) {
        memcpy(p, &result->detections[i], sizeof(FlockNfcDetection));
        p += sizeof(FlockNfcDetection);
    }

    return total_size;
}

// ============================================================================
// Heartbeat & Error
// ============================================================================

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

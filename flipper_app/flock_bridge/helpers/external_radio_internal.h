#pragma once

#include "external_radio.h"
#include <string.h>
#include <stdlib.h>

// ============================================================================
// Internal Definitions
// ============================================================================

#define TAG "ExternalRadio"

// RX state machine states
typedef enum {
    RxStateWaitStart,
    RxStateLenHigh,
    RxStateLenLow,
    RxStateCmd,
    RxStatePayload,
    RxStateCrc,
} ExternalRadioRxState;

// ============================================================================
// Internal Structure
// ============================================================================

struct ExternalRadioManager {
    ExternalRadioConfig config;
    ExternalRadioState state;
    ExternalRadioType detected_type;
    ExtRadioInfo radio_info;

    // Serial
    FuriHalSerialHandle* serial;
    bool serial_active;

    // RX state machine
    ExternalRadioRxState rx_state;

    uint16_t rx_payload_len;
    uint16_t rx_payload_idx;
    uint8_t rx_cmd;
    uint8_t rx_buffer[EXT_RADIO_MAX_PAYLOAD];
    uint8_t rx_crc;

    // Sync command response
    FuriSemaphore* response_sem;
    uint8_t* sync_response_buf;
    size_t* sync_response_len;
    size_t sync_response_max;
    volatile bool sync_waiting;

    // Timing
    uint32_t last_heartbeat;
    uint32_t last_rx_time;

    // Thread
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool running;
    volatile bool should_stop;
};

// ============================================================================
// Internal Functions (implemented in external_radio_protocol.c)
// ============================================================================

/**
 * Serial RX callback for UART protocol handling.
 */
void external_radio_serial_callback(
    FuriHalSerialHandle* handle,
    FuriHalSerialRxEvent event,
    void* context);

/**
 * Worker thread entry point.
 */
int32_t external_radio_worker(void* context);

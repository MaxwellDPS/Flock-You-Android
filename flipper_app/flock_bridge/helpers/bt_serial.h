#pragma once

#include "../flock_bridge_app.h"

/**
 * Bluetooth Serial interface for Flock Bridge.
 *
 * Uses the Flipper's built-in Bluetooth Serial Profile (SPP-like over BLE)
 * to communicate with the Android app.
 */

// Forward declaration
typedef struct FlockBtSerial FlockBtSerial;

/**
 * Allocate Bluetooth Serial handler.
 */
FlockBtSerial* flock_bt_serial_alloc(void);

/**
 * Free Bluetooth Serial handler.
 */
void flock_bt_serial_free(FlockBtSerial* bt);

/**
 * Start Bluetooth Serial service.
 */
bool flock_bt_serial_start(FlockBtSerial* bt);

/**
 * Stop Bluetooth Serial service.
 */
void flock_bt_serial_stop(FlockBtSerial* bt);

/**
 * Send data over Bluetooth Serial.
 */
bool flock_bt_serial_send(FlockBtSerial* bt, const uint8_t* data, size_t length);

/**
 * Check if Bluetooth is connected.
 */
bool flock_bt_serial_is_connected(FlockBtSerial* bt);

/**
 * Set data received callback.
 */
void flock_bt_serial_set_callback(
    FlockBtSerial* bt,
    void (*callback)(void* context, uint8_t* data, size_t length),
    void* context);

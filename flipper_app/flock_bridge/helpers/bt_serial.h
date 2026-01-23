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

/**
 * Set connection state change callback.
 */
void flock_bt_serial_set_state_callback(
    FlockBtSerial* bt,
    void (*callback)(void* context, bool connected),
    void* context);

/**
 * Pause Bluetooth Serial to allow BLE scanning.
 * The profile is stopped but the BT record remains open for quick resume.
 * Returns true if paused successfully (or was already paused).
 */
bool flock_bt_serial_pause(FlockBtSerial* bt);

/**
 * Resume Bluetooth Serial after BLE scanning.
 * Restarts the serial profile and begins advertising again.
 * Returns true if resumed successfully.
 */
bool flock_bt_serial_resume(FlockBtSerial* bt);

/**
 * Check if Bluetooth Serial is currently paused.
 */
bool flock_bt_serial_is_paused(FlockBtSerial* bt);

/**
 * Check if Bluetooth Serial is running (started and not paused).
 */
bool flock_bt_serial_is_running(FlockBtSerial* bt);

/**
 * Check if Bluetooth Serial is advertising and waiting for connection.
 */
bool flock_bt_serial_is_advertising(FlockBtSerial* bt);

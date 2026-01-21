#pragma once

#include "../flock_bridge_app.h"

/**
 * USB CDC (Communication Device Class) interface for Flock Bridge.
 *
 * This provides an alternative to Bluetooth Serial for communicating
 * with the Android app when connected via USB cable.
 *
 * Benefits of USB over Bluetooth:
 * - Higher bandwidth (up to 12 Mbps vs ~2 Mbps for BLE)
 * - Lower latency
 * - No pairing required
 * - Charges the Flipper while connected
 *
 * The protocol remains identical - the same binary messages are sent
 * whether using BT or USB.
 *
 * Time-Multiplexing with IR Scanner:
 * - USB CDC in dual mode uses DMA/timer resources that can conflict with IR
 * - When IR scanning is needed, USB CDC can be paused temporarily
 * - This is similar to how BLE scanning pauses BT serial
 */

// Already declared in flock_bridge_app.h
// FlockUsbCdc* flock_usb_cdc_alloc(void);
// void flock_usb_cdc_free(FlockUsbCdc* usb);
// bool flock_usb_cdc_start(FlockUsbCdc* usb);
// void flock_usb_cdc_stop(FlockUsbCdc* usb);
// bool flock_usb_cdc_send(FlockUsbCdc* usb, const uint8_t* data, size_t length);
// void flock_usb_cdc_set_callback(FlockUsbCdc* usb, ...);

/**
 * Pause USB CDC to allow IR scanning.
 * Temporarily switches from dual CDC mode to single mode,
 * freeing DMA/timer resources for the IR receiver.
 * Returns true if paused successfully (or was already paused).
 */
bool flock_usb_cdc_pause(FlockUsbCdc* usb);

/**
 * Resume USB CDC after IR scanning.
 * Restores dual CDC mode and resumes RX thread.
 * Returns true if resumed successfully.
 */
bool flock_usb_cdc_resume(FlockUsbCdc* usb);

/**
 * Check if USB CDC is currently paused.
 */
bool flock_usb_cdc_is_paused(FlockUsbCdc* usb);

/**
 * Check if USB CDC is running (started and not paused).
 */
bool flock_usb_cdc_is_running(FlockUsbCdc* usb);

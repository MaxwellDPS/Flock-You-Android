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
 */

// Already declared in flock_bridge_app.h
// FlockUsbCdc* flock_usb_cdc_alloc(void);
// void flock_usb_cdc_free(FlockUsbCdc* usb);
// bool flock_usb_cdc_start(FlockUsbCdc* usb);
// void flock_usb_cdc_stop(FlockUsbCdc* usb);
// bool flock_usb_cdc_send(FlockUsbCdc* usb, const uint8_t* data, size_t length);
// void flock_usb_cdc_set_callback(FlockUsbCdc* usb, ...);

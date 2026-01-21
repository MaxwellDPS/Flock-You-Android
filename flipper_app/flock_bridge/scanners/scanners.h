/**
 * Scanners Master Header
 *
 * Includes all scanner module headers organized by radio type.
 */

#pragma once

// Scheduler - Orchestrates all scanners
#include "scheduler/detection_scheduler.h"

// Sub-GHz Scanner (315/433/868/915 MHz)
#include "subghz/subghz_scanner.h"

// BLE Scanner (2.4 GHz Bluetooth Low Energy)
#include "ble/ble_scanner.h"

// WiFi Scanner (2.4/5 GHz via external ESP32)
#include "wifi/wifi_scanner.h"

// IR Scanner (Infrared)
#include "ir/ir_scanner.h"

// NFC Scanner (13.56 MHz)
#include "nfc/nfc_scanner.h"

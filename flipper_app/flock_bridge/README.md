# Flock Bridge

**Multi-Spectrum Wireless Security Scanner & Active Probe Platform for Flipper Zero**

![Version](https://img.shields.io/badge/version-1.2-blue)
![Platform](https://img.shields.io/badge/platform-Flipper%20Zero-orange)
![License](https://img.shields.io/badge/license-MIT-green)

Flock Bridge is a sophisticated Flipper Zero FAP (Flipper Application Package) that bridges the gap between the device's internal radio capabilities and external hardware modules. It provides comprehensive multi-spectrum wireless threat detection, analysis, and active probing capabilities, serving as the Flipper Zero component of the "Flock You" security research ecosystem.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Passive Scanners](#passive-scanners)
- [Active Probes](#active-probes)
- [WIPS Engine](#wips-engine)
- [External Hardware](#external-hardware)
- [Protocol Reference](#protocol-reference)
- [Configuration](#configuration)
- [Building](#building)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [Legal Disclaimer](#legal-disclaimer)

---

## Features

### Passive Detection
- **Sub-GHz Scanner** (315/433/868/915 MHz) - RF signal detection with protocol identification
- **BLE Scanner** (2.4 GHz) - Bluetooth Low Energy device tracking and tracker detection
- **WiFi Scanner** (2.4/5 GHz) - Network discovery via external ESP32
- **IR Scanner** - Infrared signal capture and replay detection
- **NFC Scanner** (13.56 MHz) - Card and tag detection with type identification

### Active Probing
- RF signal replay and transmission
- TPMS wake-up probes
- WiFi probe requests
- Traffic light preemption testing
- Wireless keyboard/mouse injection
- Physical access control testing (Wiegand, MagSpoof, iButton)

### Security Analysis
- **WIPS Engine** - Real-time WiFi Intrusion Prevention System
- Evil Twin detection
- Deauthentication flood monitoring
- Rogue AP identification
- Weak encryption alerts

### Connectivity
- USB CDC serial communication (primary)
- Bluetooth Serial support (optional)
- External radio module integration

---

## Requirements

### Hardware
- Flipper Zero (firmware 1.x or compatible)
- **Optional:** ESP32-WROOM/WROVER module for WiFi scanning
- **Optional:** External CC1101/nRF24L01+ for extended radio capabilities

### Software
- [ufbt](https://github.com/flipperdevices/flipperzero-ufbt) - Flipper Zero build tool
- Python 3.8+ (for tests)

---

## Installation

### From Release
1. Download the latest `.fap` file from Releases
2. Copy to your Flipper Zero: `/ext/apps/Tools/flock_bridge.fap`
3. Launch from Apps → Tools → Flock Bridge

### From Source
```bash
# Clone the repository
git clone https://github.com/FlockYou/Flock-You-Android.git
cd Flock-You-Android/flipper_app/flock_bridge

# Build with ufbt
make build

# Install and launch on connected Flipper
make install
```

---

## Quick Start

1. **Launch the app** on your Flipper Zero
2. **Connect via USB** to your computer or Android device running the Flock You app
3. **Navigate** using the Flipper's controls:
   - Main menu shows scanner status
   - Press OK to toggle scanners on/off
   - Navigate to specific scanner views for detailed results

### Connection Modes

| Mode | Interface | Best For |
|------|-----------|----------|
| USB CDC | USB-C cable | Desktop/laptop connection, fastest data rates |
| Bluetooth | BT Serial | Mobile connection (currently disabled for BLE scanning) |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Flock Bridge App                         │
├─────────────────────────────────────────────────────────────┤
│  Scene Manager          │  View Dispatcher                  │
│  (UI Navigation)        │  (Display Rendering)              │
├─────────────────────────────────────────────────────────────┤
│                    Detection Scheduler                       │
│         (Time-multiplexed scanner coordination)              │
├──────────┬──────────┬──────────┬──────────┬────────────────┤
│ Sub-GHz  │   BLE    │   WiFi   │    IR    │      NFC       │
│ Scanner  │ Scanner  │ Scanner  │ Scanner  │    Scanner     │
│ (CC1101) │(Internal)│ (ESP32)  │(Internal)│   (Internal)   │
├──────────┴──────────┴──────────┴──────────┴────────────────┤
│                    WIPS Engine                               │
│            (WiFi threat detection & analysis)                │
├─────────────────────────────────────────────────────────────┤
│  USB CDC  │  BT Serial  │  External Radio Manager           │
│  Handler  │   Handler   │  (ESP32/CC1101/nRF24 UART)        │
└─────────────────────────────────────────────────────────────┘
```

### Directory Structure

```
flock_bridge/
├── flock_bridge.c           # Main application entry point
├── flock_bridge_app.h       # Core data structures
├── handlers/                # Message & event handlers
│   ├── msg_handler.c        # Protocol message routing
│   ├── detection_callbacks.c# Scanner result callbacks
│   ├── settings.c           # Configuration persistence
│   └── probes/              # Active probe implementations
│       ├── subghz/          # Sub-GHz replay
│       ├── ble/             # BLE active scan
│       ├── wifi/            # WiFi probe TX
│       ├── ir/              # IR strobe
│       ├── lf/              # 125kHz TPMS
│       ├── zigbee/          # Zigbee beacon
│       ├── nrf24/           # MouseJacker
│       ├── gpio/            # Inductive loop spoof
│       └── access/          # Wiegand, MagSpoof, iButton
├── scanners/                # Passive scanner modules
│   ├── scheduler/           # Detection scheduler
│   ├── subghz/              # Sub-GHz (CC1101)
│   ├── ble/                 # Bluetooth Low Energy
│   ├── wifi/                # WiFi (external ESP32)
│   ├── ir/                  # Infrared
│   └── nfc/                 # NFC
├── helpers/                 # Support modules
│   ├── usb_cdc.c            # USB serial communication
│   ├── bt_serial.c          # Bluetooth serial
│   ├── wips_engine.c        # WiFi IPS
│   └── external_radio.c     # External module manager
├── protocol/                # Binary protocol implementation
│   ├── flock_protocol.h     # Protocol definitions
│   ├── proto_serialize.c    # Message encoding
│   └── proto_parse.c        # Message decoding
├── scenes/                  # UI scene handlers
└── tests/                   # Python test suite
```

---

## Passive Scanners

### Sub-GHz Scanner

Monitors Sub-GHz radio frequencies for RF signals.

**Frequencies Monitored:**
| Frequency | Common Uses |
|-----------|-------------|
| 300 MHz | Low-end devices |
| 315 MHz | US garage doors, car remotes |
| 390 MHz | Automotive |
| 418 MHz | Industrial |
| 426 MHz | TPMS sensors |
| 433.92 MHz | EU remotes, sensors |
| 445 MHz | Various |
| 868.35 MHz | EU devices |
| 915 MHz | US ISM band |
| 925 MHz | High-end devices |

**Detected Signal Types:**
- Remote controls (car, garage)
- Weather sensors
- TPMS (tire pressure)
- Pager signals
- Replay attacks
- RF jamming

**Supported Protocols (19+):**
Keeloq, Princeton, Nice Flo, Nice Flor-S, CAME, CAME Twee, FAAC SLH, GateTX, Hormann, Linear, Megacode, Security+, Holtek, Chamberlain, TPMS, Oregon Scientific, Acurite, LaCrosse

**Configuration:**
- RSSI threshold: -90 dBm default
- Replay detection: enabled
- Jamming detection: enabled
- Hop interval: 2500ms per frequency

### BLE Scanner

Detects Bluetooth Low Energy devices and trackers.

**Detection Capabilities:**
| Tracker Type | Detection Method |
|--------------|------------------|
| Apple AirTag | Manufacturer data pattern |
| Apple Find My | Service UUID |
| Tile | Manufacturer ID 0x00C7 |
| Samsung SmartTag | Manufacturer ID 0x0075 |
| Chipolo | Manufacturer ID 0x0305 |

**BLE Spam Detection:**
- Fake AirPods popups
- Android FastPair spam
- Windows Swift Pair spam
- BLE DoS attacks

**Features:**
- Following detection (tracks devices over time)
- RSSI-based proximity estimation
- Device name resolution
- Service UUID logging

**Limitations:**
The internal Flipper BLE can only do RF test mode (RSSI detection). Full advertisement parsing requires external ESP32/nRF24 hardware.

### WiFi Scanner (External ESP32)

Requires external ESP32 module connected via UART.

**Scan Modes:**
- **Passive** - Listen only (stealthy)
- **Active** - Send probe requests (faster discovery)
- **Monitor** - Full raw frame capture

**Detected Information:**
- SSID, BSSID, channel
- Signal strength (RSSI)
- Security type (Open, WEP, WPA, WPA2, WPA3, Enterprise)
- Hidden network detection
- Protected Management Frames (PMF) support
- Client count estimation

**Advanced Features:**
- Probe request monitoring
- Deauthentication frame detection
- Channel hopping
- Configurable dwell time

### IR Scanner

Passive infrared signal detection.

**Supported Protocols:**
NEC, NEC Extended, Samsung32, RC5, RC5X, RC6, SIRC, SIRC15, SIRC20, Kaseikyo, RCA

**Detection Types:**
- Normal button presses
- Repeated signals (button held)
- Brute force attacks (rapid different codes)
- Replay attacks (suspicious repetition)

### NFC Scanner

Passive NFC card and tag detection.

**Supported Types:**
- **Type A (ISO14443A):** MIFARE Classic, Ultralight, DESFire, Plus, NTAG
- **Type B (ISO14443B):** Various cards
- **Type F (FeliCa):** Sony FeliCa cards
- **Type V (ISO15693):** Vicinity cards

**Card Identification:**
- MIFARE Classic 1K/4K
- MIFARE Ultralight
- MIFARE DESFire
- MIFARE Plus
- NTAG 213/215/216
- Payment cards
- Transit cards
- Access control cards
- Phone NFC

---

## Active Probes

Active probes transmit signals for security testing. **Use responsibly and only on systems you own or have authorization to test.**

### Sub-GHz Replay (`0x14`)
Replay captured RF signals for testing replay attack defenses.

```
Payload: [freq_4B][repeat_count_1B][raw_data...]
- Frequency: 300-928 MHz (validated)
- Repeat count: 1-100
- Raw data: duration pairs in microseconds
```

### LF Probe / Tire Kicker (`0x0E`)
125kHz carrier for TPMS sensor wake-up testing.

```
Payload: [duration_ms_2B]
- Duration: 10-10,000 ms
- 50% duty cycle carrier
```

### WiFi Probe TX (`0x10`)
Send WiFi probe requests via external ESP32.

```
Payload: [ssid_len_1B][ssid...]
- SSID: 1-32 bytes
- Requires external ESP32
```

### IR Strobe / Opticom (`0x0F`)
Traffic light preemption signal testing.

```
Payload: [frequency_1B][duty_1B][duration_ms_2B]
- Frequency: 10 Hz (low) or 14 Hz (high priority)
- Duty cycle: 0-100%
- Duration: 100-10,000 ms
```

### BLE Active Scan (`0x11`)
Force BLE devices to send scan responses.

```
Payload: [mode_1B]
- Mode: 0 = passive, 1 = active
```

### Zigbee Beacon (`0x12`)
Transmit Zigbee beacons for mesh mapping.

```
Payload: [channel_1B]
- Channel: 11-26 (Zigbee), 0 = hop
- Falls back to Sub-GHz if no external radio
```

### NRF24 MouseJacker (`0x18`)
Wireless keyboard/mouse keystroke injection.

```
Payload: [address_5B][keycode_count_1B][keycodes...]
- Address: 5-byte target address
- Max 64 keycodes per injection
- Requires external nRF24 hardware
```

### GPIO Pulse / Ghost Car (`0x13`)
Inductive loop sensor spoofing.

```
Payload: [freq_hz_2B][duration_ms_2B][repeat_1B]
- Frequency: 20-150 kHz typical
- Duration: 50-5000 ms
- Repeat: 1-20
```

### Wiegand Replay (`0x15`)
Access control card replay.

```
Payload: [facility_2B][card_number_4B][bit_length_1B]
- Bit lengths: 26, 34, 37, etc.
```

### MagSpoof (`0x16`)
Magnetic stripe card emulation.

```
Payload: [track1_len_1B][track1...][track2_len_1B][track2...]
- Track 1: max 79 chars
- Track 2: max 40 chars
```

### iButton Emulate (`0x17`)
Dallas 1-Wire key emulation.

```
Payload: [key_id_8B]
- 8-byte DS1990A compatible key
```

---

## WIPS Engine

The Wireless Intrusion Prevention System provides real-time WiFi threat detection.

### Detected Threats

| Threat | Severity | Description |
|--------|----------|-------------|
| Evil Twin | CRITICAL | Same SSID, different BSSID |
| Deauth Flood | HIGH | DoS via deauthentication frames |
| Karma Attack | HIGH | AP responding to all probes |
| Rogue AP | MEDIUM | Spoofed legitimate network |
| Hidden + Strong | MEDIUM | Suspicious hidden network |
| Weak Encryption | LOW | WEP or legacy WPA |
| Suspicious Open | LOW | Potential honeypot |
| MAC Spoofing | MEDIUM | Duplicate MAC addresses |
| Signal Anomaly | INFO | Unusual RSSI patterns |
| Beacon Flood | LOW | Excessive beacons |
| Channel Interference | INFO | Spectrum congestion |

### Configuration

```c
WipsConfig config = {
    .detect_evil_twin = true,
    .detect_deauth = true,
    .detect_karma = true,
    .detect_rogue_ap = true,
    .hidden_network_rssi_threshold = -55,  // dBm
    .deauth_detection_window = 5000,       // ms
    .deauth_count_threshold = 10,
};
```

---

## External Hardware

### GINTBN Multi-Board (Recommended)

The **GINTBN Flipper Zero Modification Module** is the recommended expansion board, providing:

- **ESP32** - WiFi scanning and monitor mode
- **CC1101** - High-gain extended Sub-GHz (wider range than internal)
- **nRF24L01+** - 2.4GHz sniffer and MouseJacker support

**Features:**
- Plug-and-play via GPIO header
- Pre-flashed with ESP32 Marauder firmware (replaceable with Flock firmware)
- SD card slot for data storage
- All three radios accessible simultaneously

**Flashing Flock Firmware:**
```bash
# Navigate to firmware directory
cd flipper_app/esp32_firmware

# Flash using Arduino IDE or PlatformIO
# Select board: ESP32 Dev Module
# Upload: flock_multiboard.ino
```

### Supported Modules

| Module | Interface | Capabilities |
|--------|-----------|--------------|
| **GINTBN Multi-Board** | UART | WiFi + Sub-GHz + nRF24 (all-in-one) |
| ESP32-WROOM/WROVER | UART | WiFi scanning, BLE extended |
| ESP8266 | UART | WiFi scanning (limited) |
| CC1101 | SPI (via ESP32) | Extended Sub-GHz |
| nRF24L01+ | SPI (via ESP32) | 2.4 GHz sniffer/injection |
| CC2500 | UART | 2.4 GHz proprietary |
| SX1276/78 | UART | LoRa long-range |

### Connection (GINTBN / ESP32)

```
Flipper GPIO Header:
┌─────────────────────────────────────┐
│  3.3V  GND  [13]TX  [14]RX         │
│                                     │
│  Pin 13 (TX) ──> ESP32 RX (GPIO 16)│
│  Pin 14 (RX) <── ESP32 TX (GPIO 17)│
│  GND ────────── GND                 │
│  3.3V ───────── 3.3V (or USB power) │
└─────────────────────────────────────┘
```

**Alternative LPUART (Pins 15/16):**
- Pin 15 (TX) → ESP32 RX
- Pin 16 (RX) ← ESP32 TX

**Baud Rate:** 115,200

### Multi-Board Internal Connections

For GINTBN-style boards, the ESP32 connects to CC1101 and nRF24 via SPI:

```
ESP32 Pin Mapping:
┌──────────────────────────────────────────┐
│ SPI Bus (Shared):                        │
│   GPIO 23 (MOSI) ─┬─> CC1101 MOSI        │
│                   └─> nRF24 MOSI         │
│   GPIO 19 (MISO) <┬── CC1101 MISO        │
│                   └── nRF24 MISO         │
│   GPIO 18 (SCK)  ─┬─> CC1101 SCK         │
│                   └─> nRF24 SCK          │
├──────────────────────────────────────────┤
│ CC1101:                                  │
│   GPIO 5  (CS)   ──> CC1101 CS           │
│   GPIO 4  (GDO0) <── CC1101 GDO0         │
│   GPIO 2  (GDO2) <── CC1101 GDO2         │
├──────────────────────────────────────────┤
│ nRF24L01+:                               │
│   GPIO 15 (CS)   ──> nRF24 CSN           │
│   GPIO 22 (CE)   ──> nRF24 CE            │
│   GPIO 21 (IRQ)  <── nRF24 IRQ           │
└──────────────────────────────────────────┘
```

### Firmware Files

| File | Description |
|------|-------------|
| `esp32_firmware/flock_multiboard.ino` | Full multi-radio firmware (recommended) |
| `esp32_firmware/flock_wifi_scanner.ino` | WiFi-only firmware (basic) |

### Capability Detection

The firmware reports capabilities based on detected hardware:

| Capability | Flag | Description |
|------------|------|-------------|
| WiFi Scan | `0x001` | Basic WiFi scanning |
| WiFi Monitor | `0x002` | Promiscuous mode |
| WiFi Deauth | `0x004` | Deauth detection |
| WiFi Inject | `0x008` | Frame injection |
| Sub-GHz RX | `0x010` | CC1101 receive |
| Sub-GHz TX | `0x020` | CC1101 transmit |
| BLE Scan | `0x040` | BLE advertisement scan |
| BLE Advertise | `0x080` | BLE advertising |
| nRF24 Sniff | `0x100` | 2.4GHz packet sniffing |
| nRF24 Inject | `0x200` | 2.4GHz packet injection |
| nRF24 MouseJack | `0x400` | Wireless keyboard attack |

### Protocol

Binary protocol with CRC8:
```
[0xAA][LEN_H][LEN_L][CMD][PAYLOAD...][CRC8]
```

**Command Ranges:**
- `0x01-0x0F` - System commands (ping, info, reset)
- `0x10-0x1F` - WiFi commands
- `0x20-0x2F` - Sub-GHz / CC1101 commands
- `0x30-0x3F` - BLE commands
- `0x40-0x4F` - nRF24 specific commands

The external radio manager auto-detects connected hardware via heartbeat.

---

## Protocol Reference

### Message Format

```
┌─────────┬─────────┬────────────────┬─────────────────┐
│ Version │  Type   │ Payload Length │     Payload     │
│  1 byte │ 1 byte  │    2 bytes     │   0-500 bytes   │
└─────────┴─────────┴────────────────┴─────────────────┘
```

- **Version:** Protocol version (currently 1)
- **Type:** Message type ID
- **Payload Length:** Big-endian, max 500 bytes
- **Payload:** Type-specific data

### Message Types

| Type | Name | Direction |
|------|------|-----------|
| 0x00 | Heartbeat | Bidirectional |
| 0x01 | WiFi Scan Request | App → Flipper |
| 0x02 | WiFi Scan Result | Flipper → App |
| 0x03 | Sub-GHz Scan Request | App → Flipper |
| 0x04 | Sub-GHz Scan Result | Flipper → App |
| 0x05 | Status Request | App → Flipper |
| 0x06 | Status Response | Flipper → App |
| 0x07 | WIPS Alert | Flipper → App |
| 0x08 | BLE Scan Request | App → Flipper |
| 0x09 | BLE Scan Result | Flipper → App |
| 0x0A | IR Scan Request | App → Flipper |
| 0x0B | IR Scan Result | Flipper → App |
| 0x0C | NFC Scan Request | App → Flipper |
| 0x0D | NFC Scan Result | Flipper → App |
| 0x0E-0x18 | Active Probes | App → Flipper |
| 0x20-0x22 | Configuration | App → Flipper |
| 0xFF | Error Response | Flipper → App |

### Status Response (0x06)

```c
struct {
    uint8_t protocol_version;
    uint8_t wifi_connected;
    uint8_t subghz_ready;
    uint8_t ble_ready;
    uint8_t ir_ready;
    uint8_t nfc_ready;
    uint8_t battery_percent;
    uint32_t uptime_seconds;
    uint16_t wifi_scan_count;
    uint16_t subghz_detection_count;
    uint16_t ble_scan_count;
    uint16_t ir_detection_count;
    uint16_t nfc_detection_count;
    uint16_t wips_alert_count;
}
```

---

## Configuration

### Persistent Settings

Settings are stored at `/ext/apps/flock_bridge/settings.bin`.

```c
struct FlockRadioSettings {
    RadioSourceMode subghz_source;  // Auto/Internal/External/Both
    RadioSourceMode ble_source;
    RadioSourceMode wifi_source;    // External only
    bool enable_subghz;
    bool enable_ble;
    bool enable_wifi;
    bool enable_ir;
    bool enable_nfc;
};
```

### Scanner Defaults

| Scanner | Default State | RSSI Threshold |
|---------|---------------|----------------|
| Sub-GHz | Enabled | -90 dBm |
| BLE | Enabled | -85 dBm |
| WiFi | Enabled (if ESP32) | -90 dBm |
| IR | Enabled | N/A |
| NFC | Enabled | N/A |

### Timing Configuration

| Parameter | Value |
|-----------|-------|
| Sub-GHz hop interval | 2500 ms |
| BLE scan duration | 2000 ms |
| BLE scan interval | 5000 ms |
| WiFi scan interval | 10000 ms |
| Scheduler tick | 100 ms |
| Memory cleanup | 60000 ms |

---

## Building

### Prerequisites

```bash
# Install ufbt
pip install ufbt

# Update SDK
ufbt update
```

### Build Commands

```bash
# Standard build
make build

# Build and install to connected Flipper
make install

# Build and deploy (no launch)
make deploy

# Debug build
make debug

# Clean build artifacts
make clean

# Generate compile_commands.json for IDE
make compiledb
```

### Build System

The FAP uses both `ufbt` and a traditional Makefile:

- **`ufbt`** - Recommended, handles SDK automatically
- **`Makefile`** - Convenience wrapper with additional targets
- **`application.fam`** - FAP manifest with sources and dependencies

---

## Testing

### Setup

```bash
# Install test dependencies
make test-deps
```

### Running Tests

```bash
# Unit tests (no Flipper required)
make test-unit

# E2E tests (requires connected Flipper with FAP running)
make test-e2e

# All tests
make test-all

# Protocol tests only
make test-protocol

# USB CDC communication test
make test-cdc

# Coverage report
make test-coverage
```

### Test Structure

```
tests/
├── unit/                    # Unit tests
│   └── test_protocol.py     # Protocol encoding/decoding
├── e2e/                     # End-to-end tests
│   └── test_communication.py # Real device communication
├── conftest.py              # Pytest configuration
└── requirements.txt         # Python dependencies
```

---

## Project Structure

```
flock_bridge/
├── flock_bridge.c              # Entry point, lifecycle
├── flock_bridge_app.h          # Core structures
├── application.fam             # FAP manifest
├── Makefile                    # Build automation
│
├── handlers/
│   ├── handlers.h              # Handler declarations
│   ├── msg_handler.c           # Protocol message routing
│   ├── detection_callbacks.c   # Scanner result handling
│   ├── settings.c              # Configuration persistence
│   └── probes/                 # Active probes by type
│       ├── probes.h            # Probe declarations
│       ├── subghz/replay.c
│       ├── ble/scan.c
│       ├── wifi/probe.c
│       ├── ir/strobe.c
│       ├── lf/probe.c
│       ├── zigbee/beacon.c
│       ├── nrf24/inject.c
│       ├── gpio/pulse.c
│       └── access/
│           ├── wiegand.c
│           ├── magspoof.c
│           └── ibutton.c
│
├── scanners/
│   ├── scanners.h              # Scanner master header
│   ├── scheduler/              # Detection scheduler
│   │   ├── detection_scheduler.c
│   │   ├── detection_scheduler.h
│   │   ├── sched_callbacks.c
│   │   ├── sched_internal.h
│   │   └── sched_thread.c
│   ├── subghz/                 # Sub-GHz scanner
│   │   ├── subghz_scanner.c
│   │   ├── subghz_scanner.h
│   │   ├── subghz_decoder.c
│   │   └── subghz_internal.h
│   ├── ble/                    # BLE scanner
│   │   ├── ble_scanner.c
│   │   ├── ble_scanner.h
│   │   ├── ble_scanner_internal.h
│   │   └── ble_tracker_detect.c
│   ├── wifi/                   # WiFi scanner (ESP32)
│   │   ├── wifi_scanner.c
│   │   ├── wifi_scanner.h
│   │   ├── wifi_scanner_internal.h
│   │   └── wifi_deauth_detect.c
│   ├── ir/                     # IR scanner
│   │   ├── ir_scanner.c
│   │   └── ir_scanner.h
│   └── nfc/                    # NFC scanner
│       ├── nfc_scanner.c
│       └── nfc_scanner.h
│
├── helpers/
│   ├── usb_cdc.c/.h            # USB serial communication
│   ├── bt_serial.c/.h          # Bluetooth serial
│   ├── wips_engine.c/.h        # WiFi IPS
│   ├── wips_detectors.c        # WIPS threat detectors
│   ├── wips_engine_internal.h
│   ├── external_radio.c/.h     # External module manager
│   └── external_radio_internal.h
│
├── protocol/
│   ├── flock_protocol.h        # Protocol definitions
│   ├── proto_serialize.c       # Message encoding
│   ├── proto_parse.c           # Message decoding
│   └── proto_parse_probes.c    # Probe payload parsing
│
├── scenes/
│   ├── scenes.h                # Scene declarations
│   └── scene_*.c               # Individual scene handlers
│
├── icons/
│   └── flock_bridge_10px.png   # App icon
│
└── tests/
    ├── unit/
    ├── e2e/
    ├── conftest.py
    └── requirements.txt
```

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Run tests: `make test`
5. Commit: `git commit -m "Add my feature"`
6. Push: `git push origin feature/my-feature`
7. Open a Pull Request

### Code Style

- Use 4-space indentation
- Follow Flipper Zero SDK conventions
- Add comments for non-obvious logic
- Keep files under 400 lines for maintainability

---

## Legal Disclaimer

**This software is intended for authorized security research, penetration testing, and educational purposes only.**

- Only use on systems you own or have explicit written permission to test
- Respect all applicable laws and regulations
- Radio transmission may be regulated in your jurisdiction
- The authors are not responsible for misuse of this software

**Capabilities like RF replay, WiFi probing, and access control testing may be illegal without authorization. Use responsibly.**

---

## License

MIT License - See [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [Flipper Zero](https://flipperzero.one/) - The amazing hardware platform
- [ufbt](https://github.com/flipperdevices/flipperzero-ufbt) - Build toolchain
- The Flipper Zero community for SDK documentation and examples

---

**Flock Bridge** - Part of the Flock You Security Research Ecosystem

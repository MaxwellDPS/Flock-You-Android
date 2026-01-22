# Flock-You

**Open-Source Counter-Surveillance for Android**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/platform-Flipper%20Zero-orange.svg)](https://flipperzero.one)

> **"Watch the Watchers"** - Know when surveillance equipment is nearby so you can protect your privacy, document police presence, or simply understand the surveillance landscape around you.

Flock-You is a privacy-first surveillance detection application that empowers individuals to identify surveillance devices, trackers, IMSI catchers, and other monitoring equipment in their environment. All processing happens **entirely on-device** with zero cloud connectivity.

---

## Table of Contents

- [Features](#features)
- [Detection Capabilities](#detection-capabilities)
- [Privacy Architecture](#privacy-architecture)
- [Flipper Zero Integration](#flipper-zero-integration)
- [Installation](#installation)
- [Build Variants](#build-variants)
- [OEM Integration](#oem-integration)
- [Contributing](#contributing)
- [Security Considerations](#security-considerations)
- [Resources](#resources)
- [License](#license)

---

## Features

### Multi-Spectrum Detection
- **7 Detection Protocols**: BLE, WiFi, Cellular, GNSS, Ultrasonic, RF, and Satellite
- **75+ Device Signatures**: From AirTags to IMSI catchers to Flock Safety cameras
- **Real-Time Alerts**: Configurable notifications with threat severity levels
- **Stalking Detection**: Behavioral analysis to identify trackers following you

### Privacy-First Design
- **100% On-Device Processing**: No cloud, no telemetry, no analytics
- **Encrypted Database**: SQLCipher AES-256-GCM encryption
- **Ephemeral Mode**: Optional RAM-only storage that leaves no trace
- **Configurable Retention**: Control how long detection history is kept

### Security Features
- **Duress PIN**: Secondary PIN that triggers secure wipe
- **Nuke Manager**: Multi-pass secure data destruction
- **Auto-Purge**: Wipe data on screen lock or failed auth attempts
- **Dead Man's Switch**: Time-based automatic data destruction

### Hardware Integration
- **Flipper Zero**: Extended scanning via Flock Bridge FAP
- **External Radios**: ESP32, CC1101, nRF24L01+ support
- **Multi-Board Support**: GINTBN and similar expansion modules

---

## Detection Capabilities

### Bluetooth LE Trackers

| Device | Detection Method | Stalking Analysis |
|--------|------------------|-------------------|
| **Apple AirTag** | Manufacturer data + Find My UUID | Multi-location tracking, possession signal |
| **Tile** (Pro/Mate/Slim/Sticker) | Manufacturer ID 0x00C7 | Duration analysis, RSSI variance |
| **Samsung SmartTag** | Service UUID 0xFD5A | Following pattern detection |
| **Generic Trackers** | Heuristic patterns | Behavioral correlation |

**Anti-Stalking Features:**
- Detects trackers appearing at 3+ distinct locations
- "Possession signal" when tracker has strong, consistent RSSI (-40 to -55 dBm)
- Duration-based scoring for extended tracking periods
- Guidance for physical search and evidence preservation

### WiFi Surveillance

| Device Type | Detection Method |
|-------------|------------------|
| **Flock Safety ALPR** | SSID pattern `(?i)^flock[_-]?.*` |
| **License Plate Readers** | MAC OUI + SSID patterns |
| **Surveillance Vans** | Mobile hotspot patterns |
| **Hidden Cameras** | Manufacturer data analysis |
| **Rogue Access Points** | Evil twin detection, karma attack detection |
| **WiFi Pineapple** | Hak5 signature patterns |
| **Drones** | DJI, Parrot, Skydio WiFi signatures |

**Advanced Detection:**
- Following network detection (vehicle-mounted APs tracking movement)
- Deauthentication flood monitoring
- Hidden network with strong signal alerts
- Channel congestion analysis

### Cellular (IMSI Catcher Detection)

| Indicator | Severity | Description |
|-----------|----------|-------------|
| **Encryption Downgrade** | CRITICAL | Forced from 4G/5G to 2G |
| **Unknown Cell Tower** | MEDIUM-HIGH | Cell not in trusted database |
| **Stationary Cell Change** | MEDIUM | Tower change without movement |
| **Rapid Switching** | MEDIUM | Abnormally fast tower changes |
| **Signal Anomaly** | LOW-MEDIUM | Unexpected strength changes |

**Cellular Monitoring:**
- Trusted cell tower learning
- LAC/TAC anomaly detection
- MCC/MNC validation
- Cross-reference with location and movement

### GNSS/GPS Security

| Threat | Detection Method |
|--------|------------------|
| **Spoofing** | Signal uniformity, geometry validation, clock analysis |
| **Jamming** | Satellite count drop, CN0 degradation, AGC anomalies |
| **Multipath** | Urban canyon detection, variance analysis |

**Satellite Analysis:**
- Multi-constellation support (GPS, GLONASS, Galileo, BeiDou)
- Carrier-to-noise ratio monitoring
- Pseudorange consistency checks
- Position solution validation

### Ultrasonic Tracking

| Beacon Type | Frequency Range | Use Case |
|-------------|-----------------|----------|
| **Cross-Device Tracking** | 18-20 kHz | SilverPush, Alphonso |
| **Advertising Beacons** | 17.5-19 kHz | TV ad synchronization |
| **Retail Beacons** | 19-22 kHz | In-store location tracking |

**Audio Analysis:**
- FFT-based frequency detection
- Pattern matching against known beacon signatures
- Amplitude and duration filtering
- Consent-based activation (requires user acknowledgment)

### Satellite/NTN Monitoring

| Provider | Technology | Detection |
|----------|------------|-----------|
| **T-Mobile Starlink** | 3GPP Release 17 | Network identifier + timing |
| **Skylo (Pixel SOS)** | NB-IoT NTN | Service UUID + parameters |
| **Unknown NTN** | Various | Timing anomaly, unexpected handoff |

**Satellite Heuristics:**
- Unexpected satellite connection when terrestrial available
- Timing inconsistent with claimed orbital position
- Rapid/suspicious handoff patterns
- Frequency mismatch for claimed satellite type

### RF Analysis

| Detection | Method |
|-----------|--------|
| **RF Jammers** | Wideband interference patterns |
| **Drones/UAV** | 2.4/5.8 GHz control signatures |
| **Hidden Transmitters** | Unexpected RF energy detection |
| **Spectrum Anomalies** | Baseline deviation analysis |

---

## Privacy Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      YOUR DEVICE                             │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │   BLE    │  │   WiFi   │  │ Cellular │  │   GNSS   │    │
│  │ Scanner  │  │ Scanner  │  │ Monitor  │  │ Monitor  │    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘    │
│       │             │             │             │           │
│       └─────────────┴──────┬──────┴─────────────┘           │
│                            │                                 │
│                   ┌────────▼────────┐                       │
│                   │   Detection     │                       │
│                   │   Processing    │                       │
│                   └────────┬────────┘                       │
│                            │                                 │
│                   ┌────────▼────────┐                       │
│                   │  Threat Scoring │                       │
│                   │     Engine      │                       │
│                   └────────┬────────┘                       │
│                            │                                 │
│                   ┌────────▼────────┐                       │
│                   │   SQLCipher     │                       │
│                   │  Encrypted DB   │                       │
│                   └────────┬────────┘                       │
│                            │                                 │
│                   ┌────────▼────────┐                       │
│                   │    User UI      │                       │
│                   └─────────────────┘                       │
└─────────────────────────────────────────────────────────────┘
                            ║
                     ══════╬══════  NO CONNECTION
                            ║
              ┌─────────────▼─────────────┐
              │       Cloud Services       │  ← BLOCKED
              └───────────────────────────┘
```

### Privacy Guarantees

| Feature | Implementation |
|---------|----------------|
| **No Cloud Processing** | All algorithms run locally |
| **No Telemetry** | Zero analytics or crash reporting |
| **No Network Calls** | App functions fully offline |
| **Encrypted Storage** | SQLCipher AES-256-GCM |
| **Key Protection** | Android Keystore (hardware-backed when available) |
| **Open Source** | Full code transparency |

### Data Retention Options

| Setting | Description |
|---------|-------------|
| **1 Day** | Minimal history (recommended for high-risk users) |
| **3 Days** | Default setting |
| **7 Days** | Extended analysis period |
| **30 Days** | Full historical tracking |
| **Ephemeral** | RAM only, cleared on restart |

---

## Flipper Zero Integration

Flock-You includes **Flock Bridge**, a Flipper Zero FAP (Flipper Application Package) that extends detection capabilities with dedicated hardware.

### Flock Bridge Features

**Passive Scanners:**
- Sub-GHz (315/433/868/915 MHz) - RF signal detection
- BLE - Tracker and device scanning
- WiFi (via ESP32) - Network discovery and WIPS
- IR - Infrared signal capture
- NFC - Card and tag detection

**Active Probes (Authorized Testing Only):**
- Sub-GHz replay
- WiFi probe requests
- TPMS wake-up
- Traffic light preemption testing
- Wireless keyboard injection

**WIPS Engine:**
- Evil twin detection
- Deauthentication flood monitoring
- Karma attack identification
- Weak encryption alerts

### Hardware Requirements

| Component | Purpose | Required |
|-----------|---------|----------|
| Flipper Zero | Base platform | Yes |
| ESP32 Module | WiFi scanning | Optional |
| CC1101 | Extended Sub-GHz | Optional |
| nRF24L01+ | 2.4 GHz sniffer | Optional |
| GINTBN Multi-Board | All-in-one expansion | Optional |

### Connection

```
Flipper GPIO Header → ESP32 UART
Pin 13 (TX) ──────── RX (GPIO 16)
Pin 14 (RX) ──────── TX (GPIO 17)
GND ────────────────── GND
3.3V ───────────────── 3.3V
```

See [Flock Bridge README](flipper_app/flock_bridge/README.md) for complete documentation.

---

## Installation

### Requirements

- Android 8.0 (API 26) or higher
- Location permission (required for BLE/WiFi scanning)
- Microphone permission (optional, for ultrasonic detection)

### From Release

1. Download the latest APK from [Releases](https://github.com/FlockYou/Flock-You-Android/releases)
2. Enable "Install from unknown sources" if needed
3. Install the APK
4. Grant requested permissions

### From Source

```bash
# Clone the repository
git clone https://github.com/FlockYou/Flock-You-Android.git
cd Flock-You-Android

# Build debug APK
./gradlew assembleSideloadDebug

# Install to connected device
./gradlew installSideloadDebug
```

---

## Build Variants

Flock-You supports three build flavors for different deployment scenarios:

| Variant | Use Case | Permissions |
|---------|----------|-------------|
| **Sideload** | Standard user installation | Runtime permission requests |
| **System** | Privileged system app | Pre-granted via whitelist |
| **OEM** | Platform-signed embedded app | Maximum privileges |

### Building Variants

```bash
# Sideload (standard)
./gradlew assembleSideloadRelease

# System (priv-app)
./gradlew assembleSystemRelease

# OEM (platform-signed)
./gradlew assembleOemRelease
```

### Permission Differences

| Capability | Sideload | System | OEM |
|------------|----------|--------|-----|
| WiFi scan throttling | Subject to OS limits | Can disable | Can disable |
| Continuous BLE scan | Duty-cycled | Continuous | Continuous |
| Real MAC addresses | Randomized | Available | Available |
| IMEI/IMSI access | No | No | Yes |
| Background persistence | Limited | Enhanced | Maximum |

---

## OEM Integration

Flock-You is designed for OEM white-labeling and custom ROM integration.

### Quick Integration (GrapheneOS/CalyxOS/LineageOS)

```bash
# Automated integration
./system/integrate-grapheneos.sh ~/grapheneos

# Or with pre-signed APK
./system/integrate-grapheneos.sh ~/grapheneos presigned
```

### Manual Integration

1. Copy integration files:
```bash
mkdir -p ~/grapheneos/vendor/flockyou
cp system/Android.bp ~/grapheneos/vendor/flockyou/
cp system/privapp-permissions-flockyou.xml ~/grapheneos/vendor/flockyou/
```

2. Add to `device.mk`:
```makefile
$(call inherit-product, vendor/flockyou/flockyou.mk)
```

3. Build your ROM:
```bash
source build/envsetup.sh
lunch <target>
m
```

### Customization Points

| Resource | Purpose |
|----------|---------|
| `app_name` | Application display name |
| `ic_launcher` | App icon |
| Theme colors | Brand colors |
| Default settings | Privacy/retention defaults |

See [OEM_INTEGRATION.md](OEM_INTEGRATION.md) for complete documentation including security considerations.

---

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

1. Android Studio Hedgehog (2023.1.1) or newer
2. JDK 17
3. Android SDK 34

### Running Tests

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumentation tests
./gradlew connectedAndroidTest

# Lint
./gradlew lint
```

### Commit Format

```
type(scope): description

feat(scan): add support for new device type
fix(ble): correct AirTag detection false positives
docs: update detection documentation
```

---

## Security Considerations

### The Surveillance Paradox

> To detect if you're being surveilled, this app must collect data about your environment.

**What's Stored Locally:**
- Detection history with timestamps and locations
- Trusted cell tower database
- WiFi network profiles
- BLE device signatures

**Forensic Risk:**
If your device is seized, this data reveals your location history and movement patterns. Consider:
- Using minimum retention period (1 day)
- Enabling ephemeral mode for sensitive situations
- Clearing data before high-risk scenarios
- Using duress PIN if compelled to unlock

### Trust Model

| If you trust... | Use this mode |
|-----------------|---------------|
| Only yourself (build from source) | OEM with platform signing |
| Project maintainers | System with pre-signed APK |
| Maximum caution | Sideload only |

### Verifying Builds

```bash
# Check APK signature
apksigner verify --print-certs app-release.apk

# Verify GitHub attestation
gh attestation verify app-release.apk --owner FlockYou
```

---

## Resources

### Support Organizations

| Organization | Contact | Services |
|--------------|---------|----------|
| **National DV Hotline** | 1-800-799-7233 | Confidential support |
| **NNEDV Tech Safety** | techsafety.org | Technology safety resources |
| **EFF** | eff.org | Digital privacy rights |
| **ACLU** | aclu.org | Civil liberties support |

### External Databases

| Tool | URL | Description |
|------|-----|-------------|
| DeFlock | deflock.me | ALPR camera locations |
| CellMapper | cellmapper.net | Cell tower mapping |
| OpenCellID | opencellid.org | Cell tower database |
| WiGLE | wigle.net | Wireless network mapping |

### Documentation

| Document | Description |
|----------|-------------|
| [Detection System](docs/detections/README.md) | Complete detection documentation |
| [BLE Trackers](docs/detections/BLE_TRACKER_DETECTION.md) | AirTag, Tile, SmartTag detection |
| [IMSI Catchers](docs/detections/CELLULAR_IMSI_DETECTION.md) | StingRay/Hailstorm detection |
| [GNSS Security](docs/detections/GNSS_SPOOFING_DETECTION.md) | GPS spoofing/jamming detection |
| [Threat Scoring](docs/detections/THREAT_SCORING_FRAMEWORK.md) | Scoring methodology |
| [Satellite Monitoring](docs/SATELLITE_MONITORING.md) | NTN/satellite detection |
| [Flock Bridge](flipper_app/flock_bridge/README.md) | Flipper Zero integration |
| [OEM Integration](OEM_INTEGRATION.md) | ROM builder guide |

---

## Threat Scoring

Flock-You uses an enterprise-grade threat scoring formula:

```
threat_score = base_likelihood × impact_factor × confidence
```

| Severity | Score | User Action |
|----------|-------|-------------|
| **CRITICAL** | 90-100 | Immediate action required |
| **HIGH** | 70-89 | Investigate immediately |
| **MEDIUM** | 50-69 | Monitor closely |
| **LOW** | 30-49 | Log and watch |
| **INFO** | 0-29 | Informational only |

**Confidence Adjustments:**
- +0.3 Cross-protocol correlation
- +0.2 Multiple indicators
- +0.2 Persistence over time
- -0.3 Single weak indicator
- -0.5 Known false positive pattern

---

## Legal Disclaimer

This software is intended for **authorized security research, personal privacy protection, and educational purposes**.

- Only use detection features passively
- Active probing features (Flipper Zero) require authorization
- Radio transmission may be regulated in your jurisdiction
- The authors are not responsible for misuse

---

## License

MIT License - See [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [Flipper Zero](https://flipperzero.one) - Hardware platform
- [SQLCipher](https://www.zetetic.net/sqlcipher/) - Database encryption
- [EFF](https://eff.org) - Privacy advocacy and resources
- The open-source security research community

---

<p align="center">
  <b>Flock-You: Watch the Watchers</b>
  <br/>
  <i>Privacy-first surveillance detection for Android</i>
  <br/><br/>
  <a href="https://github.com/FlockYou/Flock-You-Android/issues">Report Bug</a>
  ·
  <a href="https://github.com/FlockYou/Flock-You-Android/issues">Request Feature</a>
  ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

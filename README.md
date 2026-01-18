# Flock You - Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-blue?style=flat-square" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue?style=flat-square" alt="Target SDK">
  <img src="https://img.shields.io/badge/License-Educational-orange?style=flat-square" alt="License">
</p>

**Professional surveillance device detection for Android** 📡

An Android port of the [Flock You](https://github.com/colonelpanichacks/flock-you) ESP32 project, enabling mobile detection of Flock Safety surveillance cameras, Raven gunshot detectors, and similar surveillance devices using WiFi and Bluetooth LE scanning.

## 🎯 What It Detects

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **Flock Safety ALPR** | SSID, BLE Name, MAC OUI | 🔴 HIGH |
| **Flock Falcon/Sparrow/Condor** | SSID patterns | 🔴 HIGH |
| **Raven Gunshot Detector** | BLE Service UUIDs | 🔴 CRITICAL |
| **ShotSpotter/SoundThinking** | BLE Name | 🔴 CRITICAL |
| **Vigilant (Motorola)** | SSID pattern | 🔴 HIGH |
| **Penguin Surveillance** | SSID, BLE Name | 🟠 MEDIUM |
| **Pigvision System** | SSID, BLE Name | 🟠 MEDIUM |
| **LTE Modems (Quectel, Telit, Sierra)** | MAC OUI | 🟡 LOW |

## ✨ Features

### Multi-Method Detection
- **WiFi Scanning**: SSID pattern matching and MAC address OUI lookup
- **Bluetooth LE Scanning**: Device name patterns and service UUID fingerprinting
- **Raven Detection**: Specialized BLE service UUID detection with firmware version estimation

### Rich Device Information
When a device is detected, you'll see:
- **What it is**: Device type, manufacturer, model
- **What it does**: Capabilities (ALPR, audio surveillance, vehicle fingerprinting)
- **Why it matters**: Privacy concerns (data retention, cross-jurisdiction sharing)
- **How close**: Estimated distance based on signal strength
- **Technical details**: MAC address, SSID, firmware version

### User Interface
- 🎯 Animated radar scanning display
- 📊 Detection history with filtering
- 🗺️ Map view with detection locations
- 📱 Material 3 dark tactical theme
- 📳 Vibration alerts for new detections
- 🏷️ Threat level badges (Critical/High/Medium/Low/Info)
- 📶 Signal strength indicators with distance estimation

## 📸 Detection Examples

**Flock Safety ALPR Camera:**
```
📸 Flock Safety ALPR
━━━━━━━━━━━━━━━━━━━━━━━━
🔴 HIGH Threat (95/100)

Capabilities:
• License plate capture (up to 100 mph)
• Vehicle make/model/color identification
• Vehicle 'fingerprinting' (dents, stickers)
• Real-time NCIC hotlist alerts
• Cross-jurisdiction data sharing

⚠️ Privacy Concerns:
• Mass surveillance of vehicle movements
• 30-day data retention
• Shared across law enforcement network
• Can be integrated with Palantir
```

**Raven Gunshot Detector:**
```
🦅 Raven Gunshot Detector
━━━━━━━━━━━━━━━━━━━━━━━━
🔴 CRITICAL Threat (100/100)
Firmware: 1.3.x (Latest)

Capabilities:
• Continuous audio monitoring
• Gunshot detection and location
• Human distress/scream detection
• GPS location tracking
• AI-powered audio analysis

⚠️ Privacy Concerns:
• Constant audio surveillance
• 'Human distress' detection is vague
• Audio recordings may capture conversations
```

## 🔍 Detection Patterns

### SSID Patterns
| Pattern | Device | Score |
|---------|--------|-------|
| `flock*`, `fs_*` | Flock Safety Camera | 95 |
| `falcon*`, `sparrow*`, `condor*` | Flock Camera Models | 90 |
| `raven*`, `shotspotter*` | Gunshot Detector | 100 |
| `vigilant*` | Vigilant (Motorola) ALPR | 85 |
| `penguin*` | Penguin Surveillance | 85 |
| `pigvision*` | Pigvision System | 85 |
| `alpr*`, `lpr*cam*` | Generic ALPR | 75-80 |

### MAC Address OUI Prefixes
Flock cameras use cellular LTE modems for connectivity:

| OUI Prefix | Manufacturer | Notes |
|------------|--------------|-------|
| `50:29:4D` | Quectel | Common in Flock cameras |
| `86:25:19` | Quectel | Cellular module |
| `00:14:2D` | Telit | IoT/surveillance modems |
| `D8:C7:71` | Telit Wireless | |
| `00:14:3E` | Sierra Wireless | M2M applications |
| `00:A0:D5` | Sierra Wireless | |
| `D4:CA:6E` | u-blox | GPS/cellular modules |
| `00:10:8B` | Cradlepoint | Mobile surveillance routers |

### Raven BLE Service UUIDs
Based on [GainSec research](https://github.com/GainSec):

| UUID | Service | Data Exposed |
|------|---------|--------------|
| `0000180a-...` | Device Information | Serial, model, firmware |
| `00003100-...` | GPS Location | Lat/lon/altitude |
| `00003200-...` | Power Management | Battery, solar status |
| `00003300-...` | Network Status | LTE signal, carrier |
| `00003400-...` | Upload Statistics | Bytes sent, detection count |
| `00003500-...` | Error/Diagnostics | System health, errors |
| `00001809-...` | Health (Legacy 1.1.x) | Temperature data |
| `00001819-...` | Location (Legacy 1.1.x) | Basic location |

**Firmware Detection:**
- **1.3.x**: Has Upload Statistics + Error services
- **1.2.x**: Has GPS + Power + Network services
- **1.1.x**: Uses legacy Health/Location services

## 📱 Installation

### From Release APK
1. Download the latest APK from [Releases](https://github.com/MaxwellDPS/Flock-You-Android/releases)
2. Enable "Install from unknown sources" in Android settings
3. Install the APK

### Build from Source
```bash
git clone https://github.com/MaxwellDPS/Flock-You-Android.git
cd Flock-You-Android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Google Maps API Key (Optional)
For map features, add your API key to `AndroidManifest.xml`:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="YOUR_API_KEY" />
```

## 📋 Permissions

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | WiFi/BLE scanning requires location |
| `BLUETOOTH_SCAN` | Scan for BLE surveillance devices |
| `BLUETOOTH_CONNECT` | Read BLE device details |
| `ACCESS_WIFI_STATE` | Scan WiFi networks |
| `POST_NOTIFICATIONS` | Detection alerts |
| `VIBRATE` | Haptic feedback |
| `FOREGROUND_SERVICE` | Background scanning |

## 🏗️ Architecture

```
com.flockyou/
├── data/
│   ├── model/
│   │   ├── Detection.kt           # Detection data class
│   │   └── DetectionPatterns.kt   # Device signatures database
│   └── repository/
│       ├── Database.kt            # Room database
│       └── DetectionRepository.kt
├── service/
│   └── ScanningService.kt         # Foreground scanning service
├── ui/
│   ├── components/Components.kt   # Reusable UI components
│   ├── screens/
│   │   ├── MainScreen.kt          # Detection list + details
│   │   └── MapScreen.kt           # Detection map
│   └── theme/Theme.kt             # Dark tactical theme
└── di/AppModule.kt                # Hilt dependency injection
```

## ⚠️ Limitations

- **WiFi Throttling**: Android limits scans to 4 per 2-minute period
- **Background**: Battery optimization may affect scan frequency
- **Range**: BLE detection ~50-100m depending on environment
- **False Positives**: MAC OUI detection may flag non-surveillance LTE devices

## 🙏 Credits

- Original [Flock You](https://github.com/colonelpanichacks/flock-you) by [Colonel Panic](https://colonelpanic.tech)
- [DeFlock](https://deflock.me) - Crowdsourced ALPR location database
- [GainSec](https://github.com/GainSec) - Raven BLE service UUID research

## ⚖️ Legal Notice

**For educational and research purposes only.**

This software is designed to detect the presence of surveillance equipment using publicly broadcast wireless signals. It does not interfere with, disable, or modify any surveillance equipment.

Users are responsible for ensuring compliance with all applicable laws in their jurisdiction.

---

<p align="center">
  <b>Flock You Android: Watch the Watchers</b> 📡🔍
</p>

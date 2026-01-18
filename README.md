# Flock You - Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-blue?style=flat-square" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue?style=flat-square" alt="Target SDK">
  <img src="https://img.shields.io/badge/License-Educational-orange?style=flat-square" alt="License">
</p>

**Professional surveillance device detection for Android** 📡

An Android port of the [Flock You](https://github.com/colonelpanichacks/flock-you) ESP32 project, enabling mobile detection of Flock Safety surveillance cameras, Raven gunshot detectors, police body cameras, IMSI catchers, and similar surveillance devices using WiFi and Bluetooth LE scanning.

## 🎯 What It Detects

### Surveillance Cameras & ALPR

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **Flock Safety ALPR** | SSID, BLE Name, MAC OUI | 🔴 HIGH |
| **Flock Falcon/Sparrow/Condor** | SSID patterns | 🔴 HIGH |
| **Vigilant (Motorola)** | SSID pattern | 🔴 HIGH |
| **Genetec AutoVu** | SSID pattern | 🔴 HIGH |
| **Penguin Surveillance** | SSID, BLE Name | 🟠 MEDIUM |
| **Pigvision System** | SSID, BLE Name | 🟠 MEDIUM |

### Audio Surveillance

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **Raven Gunshot Detector** | BLE Service UUIDs | 🔴 CRITICAL |
| **ShotSpotter/SoundThinking** | BLE Name | 🔴 CRITICAL |

### Police Technology

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **StingRay/Hailstorm IMSI Catcher** | SSID pattern | 🔴 CRITICAL |
| **Cellebrite UFED** | SSID, BLE Name | 🔴 CRITICAL |
| **GrayKey** | SSID, BLE Name | 🔴 CRITICAL |
| **Axon Body Cameras** | SSID, BLE Name | 🟠 MEDIUM |
| **Motorola Body Cameras** | SSID, BLE Name | 🟠 MEDIUM |
| **WatchGuard Cameras** | SSID, BLE Name | 🟠 MEDIUM |
| **Digital Ally FirstVU** | SSID, BLE Name | 🟠 MEDIUM |
| **Motorola APX Radios** | SSID, BLE Name | 🟡 LOW |
| **L3Harris XG Radios** | SSID, BLE Name | 🟡 LOW |

### Infrastructure

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **LTE Modems (Quectel, Telit, Sierra)** | MAC OUI | 🟡 LOW |
| **Cradlepoint Routers** | MAC OUI | 🟡 LOW |
| **Getac/Toughbook MDTs** | SSID pattern | ⚪ INFO |

## ✨ Features

### Multi-Method Detection
- **WiFi Scanning**: SSID pattern matching and MAC address OUI lookup
- **Bluetooth LE Scanning**: Device name patterns and service UUID fingerprinting
- **Raven Detection**: Specialized BLE service UUID detection with firmware version estimation

### Rich Device Information
When a device is detected, you'll see:
- **What it is**: Device type, manufacturer, model
- **What it does**: Capabilities (ALPR, audio surveillance, phone forensics)
- **Why it matters**: Privacy concerns (data retention, warrantless access)
- **How close**: Estimated distance based on signal strength
- **Technical details**: MAC address, SSID, firmware version

### Customizable Notifications
- 🔔 Per-threat-level alert toggles (Critical/High/Medium/Low/Info)
- 🔊 Sound and vibration options
- 📳 Multiple vibration patterns (Default, Urgent, Gentle, Long, SOS)
- 🌙 Quiet hours with configurable schedule (Critical alerts always come through)
- 🔒 Lock screen notification control
- 📌 Persistent scanning status notification

### Detection Rule Management
- 📁 Toggle entire rule categories on/off:
  - Flock Safety (ALPR cameras, Ravens)
  - Police Technology (body cams, radios, forensics)
  - Acoustic Sensors (gunshot detectors)
  - Generic Surveillance (other patterns)
- ✏️ Add custom regex rules for local devices
- 🎚️ Set custom threat scores (0-100)
- 🔄 Enable/disable individual rules

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

**StingRay IMSI Catcher:**
```
📶 Cell Site Simulator
━━━━━━━━━━━━━━━━━━━━━━━━
🔴 CRITICAL Threat (100/100)

Capabilities:
• Capture all phone identifiers in range
• Track phone locations precisely
• Intercept calls and SMS
• Force phones to downgrade encryption
• Deny cell service selectively

⚠️ Privacy Concerns:
• Mass surveillance of all phones nearby
• Used under NDA - often hidden from courts
• Can intercept content of calls/texts
• No warrant required in many jurisdictions
```

**Cellebrite UFED:**
```
📱 Mobile Forensics Device
━━━━━━━━━━━━━━━━━━━━━━━━
🔴 CRITICAL Threat (90/100)

Capabilities:
• Bypass phone lock screens
• Extract deleted data
• Access encrypted apps
• Clone entire phone contents
• Crack passwords/PINs

⚠️ Privacy Concerns:
• Complete phone data extraction
• Often used without warrants
• Can access encrypted messaging apps
• Used at traffic stops in some jurisdictions
```

## 🔍 Detection Patterns

### SSID Patterns (75+ patterns)

| Category | Patterns | Devices |
|----------|----------|---------|
| **Flock Safety** | `flock*`, `fs_*`, `falcon*`, `sparrow*`, `condor*` | ALPR cameras |
| **Audio Surveillance** | `raven*`, `shotspotter*`, `soundthinking*` | Gunshot detectors |
| **Motorola** | `moto*`, `apx*`, `astro*`, `v300*`, `v500*`, `watchguard*`, `vigilant*` | Body cams, radios, ALPR |
| **Axon** | `axon*`, `body*`, `flex*`, `taser*`, `evidence*` | Body cams, TASERs |
| **L3Harris** | `l3harris*`, `stingray*`, `hailstorm*`, `xg*` | IMSI catchers, radios |
| **Forensics** | `cellebrite*`, `ufed*`, `graykey*`, `magnet*` | Phone extraction |
| **Other ALPR** | `genetec*`, `autovu*`, `alpr*`, `lpr*` | License plate readers |

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

## ⚙️ Settings

### Notification Settings
| Setting | Description |
|---------|-------------|
| **Master Toggle** | Enable/disable all notifications |
| **Threat Level Alerts** | Toggle alerts by severity |
| **Sound** | Play alert sound on detection |
| **Vibration Pattern** | Default, Urgent, Gentle, Long, or SOS |
| **Quiet Hours** | Silence non-critical alerts (10 PM - 7 AM default) |
| **Lock Screen** | Show alerts when phone is locked |

### Detection Rules
| Setting | Description |
|---------|-------------|
| **Flock Safety** | Toggle all Flock/Raven patterns |
| **Police Tech** | Toggle body cams, radios, forensics |
| **Acoustic Sensors** | Toggle gunshot detectors |
| **Generic Surveillance** | Toggle other patterns |
| **Custom Rules** | Add your own regex patterns |

### Scan Settings
| Setting | Description |
|---------|-------------|
| **WiFi Interval** | Time between WiFi scans (15-120s) |
| **BLE Duration** | Bluetooth scan duration (5-30s) |
| **Track Seen Devices** | Remember previously detected devices |
| **Battery Optimization** | Disable for reliable background scanning |

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
│   │   ├── Detection.kt              # Detection data class
│   │   └── DetectionPatterns.kt      # Device signatures database (75+ patterns)
│   ├── repository/
│   │   ├── Database.kt               # Room database
│   │   └── DetectionRepository.kt
│   └── RuleAndNotificationSettings.kt # DataStore preferences
├── service/
│   └── ScanningService.kt            # Foreground scanning service
├── ui/
│   ├── components/Components.kt      # Reusable UI components
│   ├── screens/
│   │   ├── MainScreen.kt             # Detection list + radar
│   │   ├── MapScreen.kt              # Detection map
│   │   ├── SettingsScreen.kt         # Main settings
│   │   ├── NotificationSettingsScreen.kt # Alert customization
│   │   ├── RuleSettingsScreen.kt     # Rule management
│   │   ├── NearbyDevicesScreen.kt    # All nearby devices
│   │   └── DetectionPatternsScreen.kt # View all patterns
│   └── theme/Theme.kt                # Dark tactical theme
└── di/AppModule.kt                   # Hilt dependency injection
```

## ⚠️ Limitations

- **WiFi Throttling**: Android limits scans to 4 per 2-minute period
- **Background**: Battery optimization may affect scan frequency
- **Range**: BLE detection ~50-100m depending on environment
- **False Positives**: MAC OUI detection may flag non-surveillance LTE devices
- **Police Tech**: Body cameras only detectable when WiFi/BLE is enabled (usually during sync)

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

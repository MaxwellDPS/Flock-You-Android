# Flock You Android
## Multi-layered surveillance device detection for Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-blue?style=flat-square" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue?style=flat-square" alt="Target SDK">
  <img src="https://img.shields.io/badge/License-MIT-orange?style=flat-square" alt="License">
  <br/>
  <a href="https://github.com/MaxwellDPS/Flock-You-Android/actions/workflows/android-ci.yml">
    <img src="https://github.com/MaxwellDPS/Flock-You-Android/actions/workflows/android-ci.yml/badge.svg" alt="Android CI/CD">
  </a>
  <a href="https://github.com/MaxwellDPS/Flock-You-Android/attestations">
    <img src="https://img.shields.io/badge/SLSA-Attested-brightgreen?style=flat-square&logo=slsa" alt="SLSA Attested">
  </a>
</p>

<p align="center">
  <h3 align="center">Watch the Watchers, document injustices, ensure accountability.</h3>
  
  <p align="center"><b style="color: #eb2a2a;">FUCK</b> Palantir, <b style="color: #eb2a2a;">FUCK</b> ICE, <b style="color: #eb2a2a;">FUCK</b> ANY FASCIST PRICK.</p>
  <p align="center">WE SEE YOU, <b>WE WONT FORGET <b style="color: #eb2a2a;">YOUR ACTIONS</b></b></p>

   <p align="center"><b>Brought to you by <span style="color: #0883f6">CHAOS.CORP</span></b></p>
</p>

---

> Inspired by the [Flock You](https://github.com/colonelpanichacks/flock-you) from @colonelpanichacks's ESP32 project. 
This Android application extends the concept with significantly expanded detection capabilities. Beyond WiFi and Bluetooth LE scanning, it incorporates **cellular network analysis**, **satellite connection monitoring**, **RF signal analysis**, **rogue WiFi detection**, and **ultrasonic beacon detection**.

## Screenshots

| Main Screen | Detection Timeline | Satellite Monitor |
|:-----------:|:------------------:|:-----------------:|
| ![Main Screen](screenshots/main_screen.png) | ![Timeline](screenshots/timeline.png) | ![Satellite](screenshots/satellite.png) |

| Cellular Monitor | Settings | Rule Settings |
|:----------------:|:--------:|:-------------:|
| ![Cellular](screenshots/cellular.png) | ![Settings](screenshots/settings.png) | ![Rules](screenshots/rules.png) |

## 🎯 What It Detects

### WiFi & Bluetooth LE Detection

#### Surveillance Cameras & ALPR

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **Flock Safety ALPR** | SSID, BLE Name, MAC OUI | 🔴 HIGH |
| **Flock Falcon/Sparrow/Condor** | SSID patterns | 🔴 HIGH |
| **Vigilant (Motorola)** | SSID pattern | 🔴 HIGH |
| **Genetec AutoVu** | SSID pattern | 🔴 HIGH |
| **Penguin Surveillance** | SSID, BLE Name | 🟠 MEDIUM |
| **Pigvision System** | SSID, BLE Name | 🟠 MEDIUM |

#### Audio Surveillance

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **Raven Gunshot Detector** | BLE Service UUIDs | 🔴 CRITICAL |
| **ShotSpotter/SoundThinking** | BLE Name | 🔴 CRITICAL |

#### Police Technology

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **StingRay/Hailstorm IMSI Catcher** | SSID pattern | 🔴 CRITICAL |
| **Cellebrite UFED** | SSID, BLE Name | 🔴 CRITICAL |
| **GrayKey** | SSID, BLE Name | 🔴 CRITICAL |
| **Axon Body Cameras** | SSID, BLE Name, Signal Trigger | 🟠 MEDIUM |
| **Motorola Body Cameras** | SSID, BLE Name | 🟠 MEDIUM |
| **WatchGuard Cameras** | SSID, BLE Name | 🟠 MEDIUM |
| **Digital Ally FirstVU** | SSID, BLE Name | 🟠 MEDIUM |
| **Motorola APX Radios** | SSID, BLE Name | 🟡 LOW |
| **L3Harris XG Radios** | SSID, BLE Name | 🟡 LOW |
| **Police Lightbars** | BLE (Whelen, Federal Signal, Code 3) | 🟡 LOW |

#### Infrastructure

| Device Type | Detection Methods | Threat Level |
|-------------|-------------------|--------------|
| **LTE Modems (Quectel, Telit, Sierra)** | MAC OUI | 🟡 LOW |
| **Cradlepoint Routers** | MAC OUI | 🟡 LOW |
| **Getac/Toughbook MDTs** | SSID pattern | ⚪ INFO |

### Cellular Network Monitoring (IMSI Catcher Detection)

Real-time analysis of cellular connections with context-aware anomaly detection:

| Anomaly Type | Threat Level | Description |
|--------------|--------------|-------------|
| **Encryption Downgrade** | 🔴 CRITICAL | Network forced from 4G/5G to 2G (weak encryption) |
| **Suspicious Network ID** | 🔴 CRITICAL | Connected to test MCC/MNC (001-01, 999-99) |
| **Rapid Cell Switching** | 🔴 HIGH | Abnormal tower handoffs (5-12/min based on movement) |
| **Signal Spike** | 🔴 HIGH | Sudden signal change (>25 dBm in 5 seconds) |
| **Unexpected Cell Change** | 🟠 MEDIUM | Tower changed without user movement |
| **Location Area Anomaly** | 🟠 MEDIUM | LAC/TAC changed unexpectedly |
| **Unknown Cell Tower** | 🟡 LOW | First time seeing this cell ID |

**Features:**
- Trusted cell tower learning (100-entry history)
- Movement-aware analysis using GPS
- Confidence scoring to reduce false positives
- Global cooldown to prevent alert spam

### Satellite Connection Monitoring (NTN Detection)

Detects suspicious satellite network activity:

| Anomaly Type | Threat Level | Description |
|--------------|--------------|-------------|
| **Forced Satellite Handoff** | 🔴 CRITICAL | Suspicious switch to satellite from terrestrial |
| **Unexpected NTN Connection** | 🔴 HIGH | Satellite connection when terrestrial available |
| **T-Mobile Starlink D2C** | 🟠 MEDIUM | Direct-to-cell satellite detection |
| **Skylo NTN (Pixel 9/10)** | 🟠 MEDIUM | Satellite SOS network detection |
| **Timing Advance Anomaly** | 🟠 MEDIUM | Ephemeris mismatch detection |
| **3GPP NTN Parameters** | 🟡 LOW | Release 17 band detection (L-band, S-band) |

### Rogue WiFi Monitoring

Detects evil twin access points and wireless surveillance:

| Threat Type | Threat Level | Description |
|-------------|--------------|-------------|
| **Evil Twin AP** | 🔴 CRITICAL | Same SSID from different MAC addresses |
| **Deauth Attack** | 🔴 HIGH | Rapid WiFi disconnections (5+ in 1 minute) |
| **Hidden Camera AP** | 🟠 MEDIUM | IoT camera SSID patterns and OUI prefixes |
| **Surveillance Van** | 🟠 MEDIUM | Mobile hotspot surveillance patterns |
| **Suspicious Open Network** | 🟠 MEDIUM | High-risk public WiFi patterns |
| **Signal Strength Anomaly** | 🟡 LOW | Unusually strong signals from unknown APs |

### RF Signal Analysis

Spectrum-based threat detection:

| Threat Type | Threat Level | Description |
|-------------|--------------|-------------|
| **RF Jammer** | 🔴 CRITICAL | Sudden drops in all wireless signals |
| **Drone Detection** | 🟠 MEDIUM | DJI, Parrot, Skydio, Autel, and 15+ manufacturers |
| **Dense Network Area** | 🟠 MEDIUM | High concentration of networks (30+ = surveillance area) |
| **Hidden Transmitter** | 🟡 LOW | Continuous RF sources |

**Supported Drone Manufacturers:**
DJI (Phantom, Mavic, Spark, Inspire, Mini), Parrot (Anafi, Bebop), Skydio, Autel (EVO series), Yuneec, Hubsan, Holy Stone, Potensic, and police/tactical drones.

### Ultrasonic Beacon Detection

Cross-device tracking via inaudible audio (17.5-22 kHz):

| Threat Type | Threat Level | Description |
|-------------|--------------|-------------|
| **SilverPush Beacon** | 🔴 HIGH | Known advertising tracking beacon |
| **Alphonso Beacon** | 🔴 HIGH | TV/retail audio tracking |
| **Unknown Ultrasonic** | 🟠 MEDIUM | Persistent beacon (500ms+ duration) |

**Privacy Note:** Analyzes frequency spectrum only - does NOT record or store audio content.

## ✨ Features

### Detection Methods
- **WiFi Scanning**: SSID pattern matching and MAC address OUI lookup
- **Bluetooth LE Scanning**: Device name patterns and service UUID fingerprinting
- **Raven Detection**: Specialized BLE service UUID detection with firmware version estimation
- **Axon Signal Detection**: Monitors BLE advertising rate spikes (20+ packets/sec) indicating body camera activation
- **Cellular Monitoring**: Real-time IMSI catcher detection with trusted cell learning
- **Satellite Monitoring**: NTN connection analysis for suspicious handoffs
- **Rogue WiFi Detection**: Evil twin AP and deauthentication attack detection
- **RF Signal Analysis**: Drone detection and jammer identification
- **Ultrasonic Detection**: Cross-device tracking beacon identification (17.5-22 kHz)

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
- **Main Screen**: Animated radar display with detection counter and last detection info
- **Detection Timeline**: Full history of all detections with threat level filtering
- **Cellular Timeline**: Real-time cellular network activity and anomaly events
- **Satellite Screen**: Satellite connectivity status and NTN anomaly history
- **RF Signals Screen**: RF environment analysis and drone detection results
- **Nearby Devices**: All detected BLE/WiFi devices including unmatched
- **Map View**: Detection locations with OpenStreetMap (no API key required)
- **Settings**: Scan intervals, monitoring toggles, battery optimization
- **Notification Settings**: Per-threat-level alerts, vibration patterns, quiet hours
- **Rule Settings**: Toggle detection categories, add custom regex rules
- **Detection Patterns**: View all 75+ built-in detection patterns
- **Material 3 dark tactical theme** with threat level color coding

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

**Satellite Anomaly (NTN Detection):**
```
🛰️ Forced Satellite Handoff
━━━━━━━━━━━━━━━━━━━━━━━━
🔴 CRITICAL Threat (95/100)

Detection:
• Unexpected switch from terrestrial to satellite
• Terrestrial coverage was available
• No user-initiated satellite mode

⚠️ Privacy Concerns:
• Possible forced network redirection
• Satellite links may have different encryption
• Traffic routing through non-standard paths
• Potential for targeted interception
```

**Drone Detection:**
```
🚁 DJI Mavic Drone
━━━━━━━━━━━━━━━━━━━━━━━━
🟠 MEDIUM Threat (60/100)

Detection Method: WiFi SSID Pattern
• SSID: "Mavic-Pro-XXXX"
• Signal: -65 dBm (~30m)

⚠️ Privacy Concerns:
• Aerial surveillance capability
• Video/photo recording
• May be equipped with tracking
• Unknown operator
```

**Ultrasonic Beacon:**
```
🔊 Cross-Device Tracking Beacon
━━━━━━━━━━━━━━━━━━━━━━━━
🔴 HIGH Threat (80/100)

Detection:
• Frequency: 18.2 kHz (inaudible)
• Duration: 2.3 seconds
• Pattern: SilverPush signature

⚠️ Privacy Concerns:
• Links your devices together
• Tracks TV/radio ad exposure
• Works across phone, tablet, computer
• No user consent required
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
| **WiFi Interval** | Time between WiFi scans (30-120s) |
| **BLE Duration** | Bluetooth scan duration (5-30s) |
| **Cellular Monitoring** | Enable/disable IMSI catcher detection |
| **Satellite Monitoring** | Enable/disable NTN connection monitoring |
| **Rogue WiFi Detection** | Enable/disable evil twin and deauth detection |
| **RF Signal Analysis** | Enable/disable drone and jammer detection |
| **Ultrasonic Detection** | Enable/disable audio beacon scanning |
| **Track Seen Devices** | Remember previously detected devices |
| **Battery Optimization** | Disable for reliable background scanning |
| **Auto-start on Boot** | Automatically start scanning when device boots |

### Advanced Mode (Dev Settings)

Enable **Advanced Mode** in Settings to access additional technical controls and improve detection capabilities:

#### Detection Tuning
Access via **Settings > Detection Tuning** to configure individual detection patterns and thresholds:

| Category | Configurable Options |
|----------|---------------------|
| **Cellular Patterns** | Toggle individual anomaly types (encryption downgrade, suspicious network ID, rapid switching, signal spikes, LAC/TAC anomaly, unknown towers) |
| **Satellite Patterns** | Toggle NTN anomaly detection (unexpected satellite, forced handoff, suspicious parameters, timing anomaly) |
| **BLE Patterns** | Toggle device categories (Flock Safety, Raven, ShotSpotter, Axon, Motorola, L3Harris, Cellebrite, GrayKey) |
| **WiFi Patterns** | Toggle detection types (police hotspots, surveillance vans, StingRay WiFi, body cam WiFi, drone WiFi) |

#### Threshold Tuning
Fine-tune detection sensitivity to reduce false positives or increase detection rate:

| Threshold | Default | Description |
|-----------|---------|-------------|
| **Signal Spike (dBm)** | 25 | Cellular signal change to trigger spike alert |
| **Rapid Switch (stationary)** | 3/min | Max cell tower switches while stationary |
| **Rapid Switch (moving)** | 8/min | Max cell tower switches while moving |
| **Trusted Cell Threshold** | 5 | Times seen before cell tower is trusted |
| **BLE Min RSSI** | -80 dBm | Minimum signal strength for BLE alerts |
| **BLE Proximity RSSI** | -50 dBm | Signal strength for proximity warnings |
| **WiFi Min Signal** | -70 dBm | Minimum signal strength for WiFi alerts |

#### Additional Advanced Features
- **Error Log**: View detailed scanning errors and subsystem status
- **Scan Statistics**: Monitor BLE/WiFi scan counts, throttling, and success rates
- **Learning Mode**: Capture signatures from unknown devices for future detection
- **Advertising Rate Detection**: Monitor BLE packet rates for Axon Signal trigger activation (20+ pps = likely active)

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

### Verify APK Attestation
All release APKs include [SLSA Build Provenance](https://slsa.dev/spec/v1.0/provenance) attestation. Verify authenticity with:
```bash
# Verify the APK was built by this repository's CI
gh attestation verify FlockYou-v*.apk --owner MaxwellDPS

# View detailed attestation info
gh attestation verify FlockYou-v*.apk --owner MaxwellDPS --format json | jq
```

### Map Features
The app uses OpenStreetMap via osmdroid for map features - no API key required. Maps work offline with cached tiles.

## 📋 Permissions

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | WiFi/BLE scanning requires location |
| `BLUETOOTH_SCAN` | Scan for BLE surveillance devices |
| `BLUETOOTH_CONNECT` | Read BLE device details |
| `ACCESS_WIFI_STATE` | Scan WiFi networks |
| `CHANGE_WIFI_STATE` | Trigger WiFi scans |
| `READ_PHONE_STATE` | Cellular network monitoring for IMSI catcher detection |
| `RECORD_AUDIO` | Ultrasonic beacon detection (frequency analysis only) |
| `POST_NOTIFICATIONS` | Detection alerts |
| `VIBRATE` | Haptic feedback |
| `FOREGROUND_SERVICE` | Background scanning (Location, Connected Device, Special Use) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start scanning on device boot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reliable background operation |
| `WAKE_LOCK` | Prevent CPU sleep during scans |

## 🔐 CI/CD & Supply Chain Security

This project uses GitHub Actions for automated builds with full supply chain security.

### Build Attestation

All APKs (debug and release) are attested using [GitHub Artifact Attestations](https://docs.github.com/en/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds) with SLSA Build Provenance. This cryptographically proves:

- **What** was built (APK hash)
- **Where** it was built (this repository)
- **How** it was built (workflow file, commit SHA)
- **Who** triggered the build

### APK Signing Options

Release builds support three signing methods (in priority order):

| Priority | Method | Use Case | Configuration |
|----------|--------|----------|---------------|
| 1️⃣ | **Production Keystore** | Play Store / production releases | Repository secrets |
| 2️⃣ | **Step CA (JWK Auth)** | Enterprise PKI / organizational signing | Step CA server + JWK provisioner |
| 3️⃣ | **Ephemeral Self-Signed** | Testing / development builds | Automatic (no config needed) |

#### Option 1: Production Keystore (Recommended for Releases)

Configure these repository secrets:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` or `.p12` keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias within the keystore |
| `KEY_PASSWORD` | Key password |

```bash
# Encode your keystore
base64 -i release-keystore.jks | pbcopy  # macOS
base64 -w 0 release-keystore.jks         # Linux
```

#### Option 2: Step CA with JWK Provisioner (Enterprise)

For organizations with their own [Step CA](https://smallstep.com/docs/step-ca/) server:

| Secret | Required | Description |
|--------|----------|-------------|
| `STEP_CA_URL` | Yes | Your Step CA URL (e.g., `https://ca.example.com`) |
| `STEP_CA_ROOT_CRT` | One of these | PEM-encoded root certificate |
| `STEP_CA_FINGERPRINT` | | Root certificate fingerprint |
| `STEP_CA_JWK_PROVISIONER` | Yes | JWK provisioner name |
| `STEP_CA_JWK_KEY` | Yes | JWK private key (JSON format) |
| `STEP_CA_JWK_PASSWORD` | No | Password for encrypted JWK key |

The workflow will:
1. Bootstrap the step CLI with your CA
2. Request a certificate using the JWK provisioner
3. Include the full certificate chain in the signing keystore
4. Sign the APK with the CA-issued certificate

#### Option 3: Ephemeral Self-Signed (Default Fallback)

When no keystore or CA is configured, the workflow automatically:
1. Generates a self-signed certificate using [Smallstep CLI](https://smallstep.com/docs/step-cli/)
2. Creates an ephemeral RSA 2048-bit key pair
3. Signs the APK for testing purposes

> ⚠️ **Note:** Ephemeral certificates are regenerated each build. The APK will have a different signature each time, which may cause issues with Android's signature verification for updates.

### Workflow Features

| Feature | Description |
|---------|-------------|
| **SLSA Attestation** | Cryptographic build provenance for all APKs |
| **Multi-method Signing** | Keystore → Step CA → Ephemeral fallback |
| **Build Summaries** | Rich job summaries with signing info and links |
| **Automated Releases** | Manual trigger with version input |
| **Parallel Jobs** | Lint and tests run alongside build |
| **Artifact Retention** | Debug (14 days), Release (90 days) |

### Triggering a Release

1. Go to **Actions** → **Android CI/CD**
2. Click **Run workflow**
3. Enable **Create a GitHub Release**
4. Enter version (e.g., `1.2.0`)
5. Optionally mark as pre-release
6. Click **Run workflow**

The workflow will build, sign, attest, and create a GitHub Release with the APK.

## 🏗️ Architecture

```
com.flockyou/
├── data/
│   ├── model/
│   │   ├── Detection.kt              # Detection data class
│   │   ├── DetectionPatterns.kt      # Device signatures database (75+ patterns)
│   │   └── OuiEntry.kt               # MAC OUI manufacturer lookup
│   ├── repository/
│   │   ├── Database.kt               # Room database
│   │   ├── DetectionRepository.kt
│   │   └── OuiRepository.kt          # OUI database management
│   ├── oui/                          # OUI database files
│   ├── DetectionSettings.kt          # Detection rule toggles
│   └── OuiSettings.kt                # OUI lookup preferences
├── monitoring/
│   └── SatelliteMonitor.kt           # NTN/satellite connection monitoring
├── service/
│   ├── ScanningService.kt            # Foreground scanning service
│   ├── CellularMonitor.kt            # IMSI catcher / cell anomaly detection
│   ├── RogueWifiMonitor.kt           # Evil twin and deauth detection
│   ├── RfSignalAnalyzer.kt           # RF analysis and drone detection
│   ├── UltrasonicDetector.kt         # Audio beacon detection
│   ├── ServiceRestartReceiver.kt     # Boot and restart handling
│   └── ServiceRestartJobService.kt   # Background service persistence
├── worker/                           # WorkManager background tasks
├── ui/
│   ├── components/
│   │   └── Components.kt             # Reusable UI components
│   ├── screens/
│   │   ├── MainScreen.kt             # Detection list + radar
│   │   ├── MainViewModel.kt          # Main screen state management
│   │   ├── MapScreen.kt              # Detection map (OpenStreetMap)
│   │   ├── SatelliteScreen.kt        # Satellite monitoring display
│   │   ├── SettingsScreen.kt         # Main settings
│   │   ├── NotificationSettingsScreen.kt # Alert customization
│   │   ├── RuleSettingsScreen.kt     # Rule management
│   │   ├── OuiSettingsSection.kt     # OUI database settings
│   │   ├── NearbyDevicesScreen.kt    # All nearby devices
│   │   └── DetectionPatternsScreen.kt # View all patterns
│   └── theme/Theme.kt                # Dark tactical theme
└── di/
    ├── AppModule.kt                  # Hilt dependency injection
    └── WorkerModule.kt               # WorkManager DI
```

## ⚠️ Limitations

- **WiFi Throttling**: Android limits scans to 4 per 2-minute period
- **Background**: Battery optimization may affect scan frequency (disable for best results)
- **Range**: BLE detection ~50-100m depending on environment
- **False Positives**: MAC OUI detection may flag non-surveillance LTE devices
- **Police Tech**: Body cameras only detectable when WiFi/BLE is enabled (usually during sync)
- **Cellular Monitoring**: Anomaly detection may produce false positives in areas with poor coverage or during normal handoffs while moving. Modern IMSI catchers are increasingly difficult to detect.
- **Satellite Detection**: Requires Android 14+ and compatible hardware. NTN features depend on carrier support.
- **Ultrasonic Detection**: Requires microphone permission. Background noise may affect accuracy. Does not record audio content.
- **RF Analysis**: Drone detection relies on WiFi scanning; cannot detect RF-silent drones.
- **Rogue WiFi**: Evil twin detection requires seeing both the legitimate and rogue AP.

## 🙏 Credits

- Original [Flock You](https://github.com/colonelpanichacks/flock-you) by [Colonel Panic](https://colonelpanic.tech)
- [DeFlock](https://deflock.me) - Crowdsourced ALPR location database
- [GainSec](https://github.com/GainSec) - Raven BLE service UUID research

## 🔋 Battery Usage Notice

This application performs continuous background scanning across multiple radio interfaces (WiFi, Bluetooth LE, cellular) and sensors (microphone for ultrasonic detection). **This will significantly impact battery life.**

For optimal detection coverage:
- Disable Android's battery optimization for this app
- Expect 15-30% additional daily battery drain depending on scan settings
- Use aggressive BLE scanning only when needed
- Consider reducing scan intervals in low-risk areas

The app uses a foreground service with wake locks to maintain reliable scanning. This is necessary for consistent detection but comes at a battery cost.

## ⚖️ Legal Notice

**For educational and research purposes only.**

This software is designed to detect the presence of surveillance equipment using publicly broadcast wireless signals. It does not interfere with, disable, or modify any surveillance equipment.

Users are responsible for ensuring compliance with all applicable laws in their jurisdiction.

## 🤖 Development Notice

This application was developed with significant assistance from AI coding tools (Claude). The codebase has been reviewed for security and functionality, but users should be aware of the AI-assisted development methodology.

---

<p align="center">
  <b>Flock You Android: Watch the Watchers</b>
  <br/>
  <i>CHAOS.CORP</i>
</p>

# Flock You - Android

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="128" height="128" alt="Flock You Logo">
</p>

**Professional surveillance camera detection for Android**

An Android port of the [Flock You](https://github.com/colonelpanichacks/flock-you) ESP32 project, enabling mobile detection of Flock Safety surveillance cameras, Raven gunshot detectors, and similar surveillance devices.

## Features

### Multi-Method Detection
- **WiFi Scanning**: Detects surveillance devices by SSID patterns and MAC address prefixes
- **Bluetooth LE Scanning**: Monitors BLE advertisements for surveillance device signatures
- **Raven Service UUID Detection**: Specialized detection for SoundThinking/ShotSpotter Raven acoustic surveillance devices using BLE service UUID fingerprinting

### Device Detection
- Flock Safety ALPR Cameras
- Penguin Surveillance Devices
- Pigvision Systems
- Raven Gunshot Detectors (with firmware version estimation)
- Generic ALPR Systems

### User Interface
- Real-time scanning status with animated radar display
- Detection history with filtering by threat level and device type
- Interactive map showing detection locations
- Detailed device information sheets
- Vibration alerts for new detections
- Threat level badges (Critical, High, Medium, Low, Info)
- Signal strength indicators

### Technical Features
- Foreground service for continuous background scanning
- Room database for persistent detection storage
- Location tracking for geotagging detections
- Material 3 design with dark tactical theme
- Jetpack Compose UI
- Hilt dependency injection

## Screenshots

[Screenshots would go here]

## Installation

### Prerequisites
- Android Studio Hedgehog or newer
- Android SDK 34
- Kotlin 1.9+
- A Google Maps API key (for map features)

### Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/flock-you-android.git
   cd flock-you-android
   ```

2. **Add your Google Maps API key**
   
   Open `app/src/main/AndroidManifest.xml` and replace `YOUR_GOOGLE_MAPS_API_KEY` with your actual API key:
   ```xml
   <meta-data
       android:name="com.google.android.geo.API_KEY"
       android:value="YOUR_ACTUAL_API_KEY" />
   ```

3. **Build and run**
   ```bash
   ./gradlew assembleDebug
   ```
   
   Or open the project in Android Studio and click Run.

## Permissions

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| `ACCESS_FINE_LOCATION` | Required for WiFi and BLE scanning |
| `ACCESS_COARSE_LOCATION` | Location context for detections |
| `BLUETOOTH_SCAN` | Scan for BLE surveillance devices |
| `BLUETOOTH_CONNECT` | Connect to BLE devices for details |
| `ACCESS_WIFI_STATE` | Scan WiFi networks |
| `POST_NOTIFICATIONS` | Alert when devices are detected |
| `VIBRATE` | Haptic feedback for alerts |
| `FOREGROUND_SERVICE` | Background scanning |

## Detection Patterns

### SSID Patterns
- `flock*` - Flock Safety cameras
- `fs_*` - Flock Safety variants  
- `penguin*` - Penguin surveillance devices
- `pigvision*` - Pigvision systems
- `alpr*` - Generic ALPR systems

### Raven Service UUIDs
Based on [GainSec research](https://github.com/GainSec):

| UUID | Service |
|------|---------|
| `0000180a-...` | Device Information (Serial, Model, Firmware) |
| `00003100-...` | GPS Location |
| `00003200-...` | Power Management (Battery/Solar) |
| `00003300-...` | Network Status (LTE/WiFi) |
| `00003400-...` | Upload Statistics |
| `00003500-...` | Error/Failure Diagnostics |
| `00001809-...` | Health Service (Legacy 1.1.x) |
| `00001819-...` | Location Service (Legacy 1.1.x) |

### Firmware Version Detection
The app automatically estimates Raven firmware versions:
- **1.3.x (Latest)**: Full suite of diagnostic services
- **1.2.x**: GPS, Power, and Network services
- **1.1.x (Legacy)**: Health Thermometer and Location services

## Architecture

```
com.flockyou/
├── data/
│   ├── model/
│   │   ├── Detection.kt        # Detection data models
│   │   └── DetectionPatterns.kt # Known device signatures
│   └── repository/
│       ├── Database.kt         # Room database & DAO
│       └── DetectionRepository.kt
├── di/
│   └── AppModule.kt            # Hilt DI module
├── service/
│   └── ScanningService.kt      # Foreground scanning service
├── ui/
│   ├── components/
│   │   └── Components.kt       # Reusable UI components
│   ├── screens/
│   │   ├── MainScreen.kt       # Main detection list
│   │   ├── MainViewModel.kt
│   │   ├── MapScreen.kt        # Detection map
│   │   └── MapViewModel.kt
│   └── theme/
│       ├── Theme.kt            # Material 3 theme
│       └── Type.kt             # Typography
├── FlockYouApplication.kt
└── MainActivity.kt
```

## Building

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

The APK will be located at `app/build/outputs/apk/`

## Known Limitations

- **WiFi Scanning**: Android limits WiFi scan frequency to 4 scans per 2-minute period
- **Background Scanning**: Battery optimizations may affect scan frequency
- **BLE Range**: Detection range is approximately 50-100 meters depending on environment
- **Map API Key**: Google Maps features require a valid API key

## Credits

This project is based on the original [Flock You](https://github.com/colonelpanichacks/flock-you) ESP32 project by [Colonel Panic](https://colonelpanic.tech).

Detection patterns are derived from:
- [DeFlock](https://deflock.me) - Crowdsourced ALPR location database
- [GainSec](https://github.com/GainSec) - Raven BLE service UUID research

## Legal Notice

This application is provided for educational and research purposes only. Please ensure compliance with all applicable laws and regulations in your jurisdiction before using this software.

**Do not use this software to:**
- Interfere with or disable surveillance equipment
- Engage in any illegal activities
- Violate others' privacy rights

## License

This project is provided as-is for educational and research purposes.

---

**Flock You Android: Watch the Watchers** 📡


# Flock-You-Android: Satellite Connectivity Monitoring Module

## Overview

This module adds comprehensive satellite connectivity detection and monitoring capabilities to the Flock-You surveillance detection app. It monitors for potentially malicious network manipulation involving satellite connections, particularly relevant for modern phones supporting Direct-to-Cell (D2D) satellite services.

## Supported Satellite Technologies

### 1. T-Mobile Starlink (T-Satellite)

**Launch Date:** July 23, 2025  
**Satellite Count:** 650+ LEO satellites (as of January 2026)  
**Coverage:** Continental US, Hawaii, Alaska, Puerto Rico (500,000+ sq miles)  
**Standard:** 3GPP Release 17

#### Network Identifiers
When connected to T-Mobile Starlink, devices display:
- `T-Mobile SpaceX`
- `T-Sat+Starlink`
- `T-Satellite`

#### Technical Specifications
| Parameter | Value |
|-----------|-------|
| Orbital Altitude | 540 km (LEO) |
| Orbital Speed | ~17,000 mph |
| Typical Latency | 30ms |
| Max Channel BW | 30 MHz |
| HARQ Processes | 32 (increased for NTN) |
| Access Technology | LTE (T-Mobile mid-band spectrum) |

#### Supported Features
- ✅ SMS Text Messaging
- ✅ MMS Picture Messages (late 2025)
- ✅ Emergency 911 Text
- ✅ Location Sharing
- ✅ Limited Data (WhatsApp, Google Maps, AllTrails, AccuWeather, X)
- ⏳ Voice Calls (coming 2026)

#### Compatible Devices (60+ devices)
- **Apple:** iPhone 14/15/16 series
- **Google:** Pixel 9/10 series
- **Samsung:** Galaxy S23/S24/S25, Z Fold/Flip 5/6
- **Motorola:** moto g, edge, razr (2024-2026 models)

### 2. Skylo NTN (Pixel Satellite SOS)

**Provider:** Skylo Technologies  
**Standard:** 3GPP Release 17 NB-IoT NTN  
**Emergency Partner:** Garmin Response

#### Supported Devices
- Pixel 9, Pixel 9 Pro, Pixel 9 Pro XL, Pixel 9 Pro Fold
- Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL
- Pixel Watch 4 (first smartwatch with NB-NTN)

#### Features
- ✅ Satellite SOS (emergency messaging)
- ✅ Location Sharing via Satellite (Android 16 - Pixel 10)
- ✅ Carrier-dependent SMS

#### Regional Availability
- United States (including Hawaii, Alaska, Puerto Rico)
- Canada
- France, Germany, Spain, Switzerland, UK
- Australia

### 3. 3GPP NTN Frequency Bands

From TS 38.101-5 and TS 36.102:

| Band | Uplink (MHz) | Downlink (MHz) | Description | Release |
|------|--------------|----------------|-------------|---------|
| n253 | 1626-1660 | 1525-1559 | MSS L-band | Rel-17 |
| n254 | 1626-1660 | 1525-1559 | MSS L-band | Rel-17 |
| n255 | 1626-1660 | 1525-1559 | MSS L-band | Rel-17 |
| n256 | 1980-2010 | 2170-2200 | MSS S-band | Rel-17 |
| n510 | 27500-29500 | 17700-20200 | Ka-band | Rel-18 |
| n511 | 27500-30000 | 17700-21200 | Ka-band | Rel-18 |
| n512 | 42500-43500 | TDD | Ka-band | Rel-18 |

## Surveillance Detection Heuristics

### SAT_001: Unexpected Satellite Connection
**Severity:** HIGH

Detects when device switches to satellite despite having good terrestrial signal.

**Indicators:**
- Recent terrestrial signal > -100 dBm
- Switch occurred < 5 seconds after good signal
- No user-initiated satellite action

**Potential Threat:** Cell site simulator forcing satellite fallback to intercept communications.

### SAT_002: Unknown Satellite Network
**Severity:** CRITICAL

Connection to unrecognized satellite network.

**Known Networks:** T-Mobile SpaceX, T-Sat+Starlink, Skylo, Globalstar, AST SpaceMobile, Lynk, Iridium, Inmarsat

**Potential Threat:** Spoofed satellite network for interception.

### SAT_003: Timing Anomaly
**Severity:** HIGH

Satellite connection timing inconsistent with claimed orbital position.

**Expected RTT:**
| Orbit Type | Min RTT | Max RTT |
|------------|---------|---------|
| LEO | 20ms | 80ms |
| MEO | 80ms | 200ms |
| GEO | 200ms | 600ms |
| Ground-based spoof | <10ms | - |

### SAT_004: Rapid Switching
**Severity:** MEDIUM

Abnormally frequent satellite handoffs (>10 per hour).

**Expected:** LEO satellites pass overhead in ~10-15 minutes, with predictable handoff patterns.

### SAT_005: Forced Downgrade
**Severity:** HIGH

Network forced from better technology (5G/LTE) to satellite when terrestrial signal was adequate.

### SAT_006: Frequency Mismatch
**Severity:** CRITICAL

Claimed satellite connection using non-NTN frequency bands.

### SAT_007: Urban Satellite
**Severity:** MEDIUM

Satellite connection activated in urban area with known cellular coverage.

## Android API Integration

### SatelliteManager API (Android 14+)

```kotlin
// Check satellite support
val hasSatellite = context.packageManager.hasSystemFeature(
    "android.hardware.telephony.satellite"
)

// Get SatelliteManager
val satelliteManager = context.getSystemService(Context.SATELLITE_SERVICE) 
    as? SatelliteManager

// Register for state changes
satelliteManager?.registerStateChangeListener(executor, listener)
```

### NT Radio Technology Constants
```kotlin
object NTRadioTechnology {
    const val UNKNOWN = 0
    const val NB_IOT_NTN = 1    // NB-IoT over NTN
    const val NR_NTN = 2        // 5G NR over NTN
    const val EMTC_NTN = 3      // eMTC over NTN
    const val PROPRIETARY = 4   // Apple/Globalstar
}
```

### Satellite Modem States
```kotlin
object ModemState {
    const val IDLE = 0
    const val LISTENING = 1
    const val DATAGRAM_TRANSFERRING = 2
    const val DATAGRAM_RETRYING = 3
    const val OFF = 4
    const val UNAVAILABLE = 5
    const val NOT_CONNECTED = 6
    const val CONNECTED = 7
    const val ENABLING_SATELLITE = 8
    const val DISABLING_SATELLITE = 9
}
```

## Global D2D Satellite Carriers

### Active Partnerships
| Carrier | Country | Provider | Status |
|---------|---------|----------|--------|
| T-Mobile | USA | Starlink/SpaceX | Active |
| One NZ | New Zealand | Starlink/SpaceX | Active |
| Verizon | USA | Skylo | Active |
| Orange | France | Skylo | Active |

### Testing/Launching
- Telstra, Optus (Australia)
- Rogers (Canada)
- KDDI (Japan)
- Salt (Switzerland)
- Entel (Chile/Peru)
- Kyivstar (Ukraine)
- VMO2 (UK)

## Implementation Files

```
flock-you-android/
└── app/src/main/java/com/flockyou/android/
    ├── monitoring/
    │   ├── SatelliteMonitor.kt           # Core monitoring service
    │   └── SatelliteDetectionHeuristics.kt # Detection rules & specs
    └── ui/screens/
        └── SatelliteScreen.kt            # UI composables
```

## Permissions Required

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-feature android:name="android.hardware.telephony.satellite" 
              android:required="false" />
```

## References

- [3GPP NTN Overview](https://www.3gpp.org/technologies/ntn-overview)
- [T-Mobile T-Satellite Support](https://www.t-mobile.com/support/coverage/satellite-support)
- [Starlink Direct to Cell](https://starlink.com/business/direct-to-cell)
- [Android SatelliteManager](https://developer.android.com/reference/android/telephony/satellite/SatelliteManager)
- [Skylo Technologies](https://www.skylo.tech)
- [3GPP TS 38.101-5](https://www.3gpp.org/DynaReport/38101-5.htm) - NR NTN requirements
- [3GPP TS 36.102](https://www.3gpp.org/DynaReport/36102.htm) - E-UTRA NTN requirements

## Privacy Implications

Satellite connectivity introduces new privacy considerations:

1. **Location Exposure:** Satellite connections inherently reveal your approximate location to the satellite network and ground stations.

2. **Metadata Collection:** Even encrypted messages reveal timing, destination, and frequency patterns.

3. **No VPN Support:** Current D2D satellite services don't support VPN connections.

4. **Government Access:** Satellite providers may be subject to lawful intercept requirements.

5. **Coverage Gaps as Features:** Being in a "dead zone" previously provided some privacy; satellite connectivity removes this.

## Changelog

### v1.0.0 (January 2026)
- Initial satellite monitoring implementation
- T-Mobile Starlink detection (650+ satellites)
- Skylo NTN detection (Pixel 9/10)
- 7 surveillance detection heuristics
- Real-time anomaly alerting
- Comprehensive UI with connection details

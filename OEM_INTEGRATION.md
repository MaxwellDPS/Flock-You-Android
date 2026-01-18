# Flock You - OEM Integration Guide

This document explains how to build and integrate Flock You in different modes:
- **Sideload**: Standard user-installable APK
- **System**: Privileged system app (installed in /system/priv-app)
- **OEM**: Platform-signed OEM embedded app (maximum privileges)

## Build Variants

The app uses Gradle product flavors to create different builds:

```bash
# Build sideload (standard) version
./gradlew assembleSideloadRelease

# Build system privileged version
./gradlew assembleSystemRelease

# Build OEM embedded version
./gradlew assembleOemRelease
```

### Build Outputs

| Variant | Output APK | Application ID |
|---------|-----------|----------------|
| Sideload Debug | `sideloadDebug/app-sideload-debug.apk` | com.flockyou.debug |
| Sideload Release | `sideloadRelease/app-sideload-release.apk` | com.flockyou |
| System Debug | `systemDebug/app-system-debug.apk` | com.flockyou.debug |
| System Release | `systemRelease/app-system-release.apk` | com.flockyou |
| OEM Debug | `oemDebug/app-oem-debug.apk` | com.flockyou.debug |
| OEM Release | `oemRelease/app-oem-release.apk` | com.flockyou |

## Permission Differences by Mode

### Sideload Mode
- Runtime permission requests required
- Subject to WiFi scan throttling (4 scans / 2 minutes)
- BLE duty cycling enforced by OS
- No IMEI/IMSI access
- Battery optimization exemption requires user action
- MAC addresses may be randomized

### System Mode (priv-app)
- Many permissions pre-granted via privapp-permissions whitelist
- Can disable WiFi scan throttling via hidden API
- BLE continuous scanning with BLUETOOTH_PRIVILEGED
- Real MAC addresses available
- Process can be more persistent
- Still no IMEI/IMSI (requires platform signature)

### OEM Mode (platform-signed)
- All privileges available
- Real-time modem access
- IMEI/IMSI access for enhanced IMSI catcher detection
- Maximum process persistence
- Full hidden API access

## System App Installation

### 1. Build the System APK

```bash
./gradlew assembleSystemRelease
```

### 2. Install Permission Whitelist

Copy the permission whitelist file to the device:

```bash
# For Android 10 and earlier
adb push system/privapp-permissions-flockyou.xml /system/etc/permissions/

# For Android 11+
adb push system/privapp-permissions-flockyou.xml /system_ext/etc/permissions/
```

### 3. Install the APK

```bash
# For Android 10 and earlier
adb push app/build/outputs/apk/system/release/app-system-release.apk /system/priv-app/FlockYou/FlockYou.apk

# For Android 11+
adb push app/build/outputs/apk/system/release/app-system-release.apk /system_ext/priv-app/FlockYou/FlockYou.apk
```

### 4. Set Permissions

```bash
# Set correct permissions
adb shell chmod 644 /system/priv-app/FlockYou/FlockYou.apk
adb shell chmod 644 /system/etc/permissions/privapp-permissions-flockyou.xml

# For Android 11+
adb shell chmod 644 /system_ext/priv-app/FlockYou/FlockYou.apk
adb shell chmod 644 /system_ext/etc/permissions/privapp-permissions-flockyou.xml
```

### 5. Reboot

```bash
adb reboot
```

## GrapheneOS Integration

Pre-built integration files are available in the `system/` directory:

| File | Purpose |
|------|---------|
| `system/Android.bp` | Soong build system module (recommended) |
| `system/Android.mk` | Legacy Make build system module |
| `system/flockyou.mk` | Device makefile include |
| `system/integrate-grapheneos.sh` | Automated integration script |
| `system/privapp-permissions-flockyou.xml` | Privileged permissions whitelist |
| `system/default-permissions-flockyou.xml` | Runtime permissions pre-grant |

### Quick Integration (Automated)

Use the provided helper script:

```bash
# Build and integrate with platform signing (OEM mode)
./system/integrate-grapheneos.sh ~/grapheneos

# Or with pre-signed APK (System mode)
./system/integrate-grapheneos.sh ~/grapheneos presigned
```

The script will:
1. Build the appropriate APK if not already built
2. Copy all necessary files to `vendor/flockyou/`
3. Configure signing mode
4. Display next steps

### Manual Integration

#### 1. Copy integration files to your source tree

```bash
mkdir -p ~/grapheneos/vendor/flockyou
cp system/Android.bp ~/grapheneos/vendor/flockyou/
cp system/privapp-permissions-flockyou.xml ~/grapheneos/vendor/flockyou/
cp system/default-permissions-flockyou.xml ~/grapheneos/vendor/flockyou/

# Build and copy the APK
./gradlew assembleOemRelease
cp app/build/outputs/apk/oem/release/app-oem-release.apk ~/grapheneos/vendor/flockyou/FlockYou.apk
```

#### 2. Add to device.mk

```makefile
# Option A: Include the makefile
$(call inherit-product, vendor/flockyou/flockyou.mk)

# Option B: Add directly
PRODUCT_PACKAGES += FlockYou
```

#### 3. Build your ROM

```bash
source build/envsetup.sh
lunch <target>
m
```

### Platform Signing (OEM Mode)

For maximum privileges (IMEI/IMSI access), the APK must be signed with the device's platform certificate. The `Android.bp` uses `certificate: "platform"` by default, which signs during the ROM build.

To manually sign an APK with platform keys:

```bash
# Using AOSP signing tools
java -jar signapk.jar platform.x509.pem platform.pk8 app-oem-release-unsigned.apk app-oem-release.apk

# Using apksigner
apksigner sign --ks platform.keystore --ks-key-alias platform app-oem-release.apk
```

### Pre-signed APK (System Mode)

If you prefer to use a pre-signed APK (with your own release key), modify `Android.bp`:

```diff
- certificate: "platform",
+ certificate: "PRESIGNED",
+ presigned: true,
```

## CalyxOS / LineageOS Integration

The same integration files work for CalyxOS and LineageOS. Use the automated script or manual process described above.

## Runtime Detection

The app automatically detects its privilege level at runtime:

```kotlin
// Check current mode
val mode = PrivilegeModeDetector.detect(context)
when (mode) {
    is PrivilegeMode.Sideload -> // Standard mode
    is PrivilegeMode.System -> // Privileged system app
    is PrivilegeMode.OEM -> // Platform-signed OEM app
}

// Check capabilities
val capabilities = ScannerFactory.getInstance(context).getCapabilities()
if (capabilities.canDisableWifiThrottling) {
    // WiFi throttling can be disabled
}
if (capabilities.hasPrivilegedPhoneAccess) {
    // IMEI/IMSI access available
}
```

## Scanner Architecture

The app uses a factory pattern to create appropriate scanners based on the detected privilege level:

```
┌─────────────────────────────────────────────────────────────┐
│                     ScannerFactory                          │
│                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │ IWifiScanner│    │IBluetoothScanner│ │ICellularScanner│  │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘     │
│         │                  │                  │             │
│    ┌────┴────┐        ┌────┴────┐        ┌────┴────┐       │
│    │         │        │         │        │         │       │
│ Standard  System   Standard  System   Standard  System     │
│ Scanner   Scanner  Scanner   Scanner  Scanner   Scanner    │
│    │         │        │         │        │         │       │
│    └────┬────┘        └────┬────┘        └────┬────┘       │
│         │                  │                  │             │
│  Sideload Mode       System/OEM Mode    System/OEM Mode    │
└─────────────────────────────────────────────────────────────┘
```

## Verification

After installation, verify the mode in the app:

1. Open Flock You
2. Go to Settings
3. Check "About" section for:
   - Build Mode (sideload/system/oem)
   - Detected Privilege Level
   - Available Capabilities

Or programmatically:

```kotlin
val summary = SystemPermissionHelper.getPermissionSummary(context)
Log.d("FlockYou", summary.toDisplayString())
```

## Troubleshooting

### Permissions Not Granted

If privileged permissions aren't granted:

1. Verify the privapp-permissions XML is in the correct location
2. Check the package name matches exactly
3. Ensure the APK is in priv-app, not regular app directory
4. Check logcat for permission denial messages:
   ```bash
   adb logcat | grep -i "permission\|flockyou"
   ```

### WiFi Throttling Not Disabled

The hidden API `setScanThrottleEnabled` may not be available on all ROMs:

1. Check if the method exists via reflection
2. Some ROMs remove or rename this method
3. Fallback behavior continues with throttled scanning

### BLE Duty Cycling Still Active

Without BLUETOOTH_PRIVILEGED, the OS will still duty-cycle scans:

1. Verify BLUETOOTH_PRIVILEGED is in the whitelist
2. Check it's actually granted: `dumpsys package com.flockyou | grep BLUETOOTH`
3. Some ROMs may override this behavior

## Security Considerations

> **Important**: System/OEM installations grant significant device access. Understand the trade-offs before deploying.

### The Privilege Paradox

Installing Flock You as a system or OEM app creates a security trade-off:

| Capability | Benefit (Detection) | Risk (If Compromised) |
|------------|---------------------|----------------------|
| `BLUETOOTH_PRIVILEGED` | Continuous BLE scanning without duty cycling | Could enumerate all nearby Bluetooth devices continuously |
| `READ_PRIVILEGED_PHONE_STATE` | IMEI/IMSI access for IMSI catcher detection | Could exfiltrate device identifiers for tracking |
| `PEERS_MAC_ADDRESS` / `LOCAL_MAC_ADDRESS` | Real MAC addresses for accurate device fingerprinting | Could deanonymize the device on networks |
| `CONNECTIVITY_INTERNAL` | Disable WiFi scan throttling | Could perform aggressive network reconnaissance |
| `PERSISTENT_ACTIVITY` | Reliable background detection | Harder to stop if compromised |
| `START_ACTIVITIES_FROM_BACKGROUND` | Alert user immediately | Could launch phishing overlays |
| Platform signature | Full hidden API access, modem interaction | Complete device compromise potential |

### Attack Surface by Installation Mode

#### Sideload Mode (Lowest Risk)
```
Attack Surface: Application sandbox only
Compromise Impact: Limited to app's data directory
Recovery: Uninstall app, clear data
```
- No privileged permissions
- Subject to all Android security restrictions
- Cannot access hardware identifiers
- Standard app sandboxing applies

#### System Mode (Medium Risk)
```
Attack Surface: Privileged app permissions
Compromise Impact: Access to privileged APIs, persistent installation
Recovery: Factory reset or reflash system partition
```
- Pre-granted privileged permissions
- Survives app data clear (must be disabled via ADB or root)
- Access to protected broadcasts
- Can read some hardware identifiers

#### OEM Mode (Highest Risk)
```
Attack Surface: Platform-level trust
Compromise Impact: Full device compromise, potential persistence across factory reset
Recovery: Full reflash of all partitions
```
- Platform signature grants implicit trust
- Can access all hidden APIs
- Can interact directly with hardware (modem, radios)
- May survive factory reset if in system partition
- Equivalent trust level to core Android components

### Specific Risk Scenarios

#### 1. Supply Chain Attack
**Risk**: Malicious code injected into the APK before signing
```
Vector: Compromised build system, dependency poisoning, or malicious contributor
Impact: Attacker gains whatever privileges the app has
```
**Mitigations**:
- Verify SLSA attestation on all APKs: `gh attestation verify FlockYou-v*.apk --owner MaxwellDPS`
- Build from source and audit changes
- Use reproducible builds when possible
- Pin dependency versions in build.gradle

#### 2. Compromised Update
**Risk**: Malicious update pushed to installed system app
```
Vector: Compromised signing key, or sideloaded malicious APK with same package name
Impact: Privilege escalation if update has higher privileges
```
**Mitigations**:
- System apps cannot be updated by user-installed APKs (signature mismatch)
- Platform-signed apps can only be updated by platform-signed APKs
- Disable "Install from unknown sources" on production devices
- Use verified boot to detect system partition modifications

#### 3. Data Exfiltration
**Risk**: App collects sensitive data and sends it to attacker
```
Vector: Malicious code in app, or compromised network library
Impact: Location history, device identifiers, nearby device information leaked
```
**Mitigations**:
- Audit network permissions and traffic
- App requests no INTERNET permission beyond what's needed
- Use network monitoring to detect unexpected connections
- Review source code for data collection patterns

#### 4. Privilege Escalation
**Risk**: Attacker uses app's privileges to attack other system components
```
Vector: IPC vulnerabilities, intent injection, or confused deputy attacks
Impact: Attacker gains access beyond app's intended scope
```
**Mitigations**:
- App exports minimal components (check AndroidManifest.xml)
- ContentProviders are not exported
- BroadcastReceivers use permission protection
- Services require signature-level permissions for binding

### Mitigation Strategies

#### For ROM Builders

1. **Audit the code** before including in your build:
   ```bash
   # Review all permissions requested
   aapt dump permissions app-oem-release.apk

   # Check exported components
   aapt dump xmltree app-oem-release.apk AndroidManifest.xml | grep -i export

   # Verify no unexpected network endpoints
   grep -r "http" app/src/main/java/
   ```

2. **Use System mode instead of OEM** unless you specifically need:
   - IMEI/IMSI access for enhanced IMSI catcher detection
   - Direct modem interaction
   - Full hidden API access

3. **Restrict permissions further** by modifying the whitelist:
   ```xml
   <!-- Minimal whitelist for detection without sensitive access -->
   <privapp-permissions package="com.flockyou">
       <permission name="android.permission.BLUETOOTH_PRIVILEGED"/>
       <permission name="android.permission.CONNECTIVITY_INTERNAL"/>
       <!-- Omit READ_PRIVILEGED_PHONE_STATE if IMSI catcher detection not needed -->
   </privapp-permissions>
   ```

4. **SELinux policy** (advanced): Create a custom SELinux domain:
   ```
   # Restrict network access
   neverallow flockyou_app { domain -flockyou_app }:tcp_socket *;

   # Restrict file access
   neverallow flockyou_app system_data_file:file write;
   ```

#### For End Users

1. **Prefer sideload mode** for personal devices unless you have a specific need for enhanced detection

2. **Verify builds** before installing:
   ```bash
   # Check APK signature
   apksigner verify --print-certs app-release.apk

   # Verify attestation
   gh attestation verify app-release.apk --owner MaxwellDPS
   ```

3. **Monitor app behavior**:
   ```bash
   # Watch for unexpected network activity
   adb shell dumpsys netstats detail | grep flockyou

   # Check what the app is accessing
   adb shell dumpsys package com.flockyou | grep -A20 "granted=true"
   ```

4. **Use a dedicated device** for high-risk scenarios rather than your primary phone

### Trust Model Summary

| If you trust... | Use this mode |
|-----------------|---------------|
| Only yourself (build from source, audit code) | OEM with platform signing |
| The project maintainers and CI/CD | System with PRESIGNED |
| No one (maximum caution) | Sideload only |

### Incident Response

If you suspect the app has been compromised:

**Sideload Mode**:
```bash
adb uninstall com.flockyou
adb shell pm clear com.flockyou  # Clear any residual data
```

**System Mode**:
```bash
adb shell pm disable-user --user 0 com.flockyou
adb shell pm uninstall -k --user 0 com.flockyou
# Full removal requires reflashing system partition
```

**OEM Mode**:
```bash
# Cannot be removed without reflashing
# Factory reset may not be sufficient
# Reflash system, system_ext, and vendor partitions
fastboot flash system system.img
fastboot flash system_ext system_ext.img
```

## Data Collection & The Surveillance Paradox

> **To detect if you're being surveilled, this app must surveil you first.**

### What Data Is Collected

The app creates a comprehensive local database of your environment:

| Data Type | What's Stored | Retention |
|-----------|---------------|-----------|
| **Location** | GPS coordinates attached to every detection event | Configurable (1-365 days, default 30) |
| **Cell Towers** | Cell ID, LAC/TAC, MCC/MNC, signal strength, timestamps | Up to 100 trusted cells |
| **WiFi Networks** | BSSID, SSID, signal strength, location where seen | Up to 500 network profiles |
| **Bluetooth Devices** | MAC address, name, service UUIDs, signal strength | Per-detection |
| **Ultrasonic Events** | Frequency, amplitude, duration, beacon type | Up to 100 events |
| **Satellite Connections** | Network type, operator, signal, connection history | Up to 1000 events |
| **RF Environment** | Network density, channel distribution, drone patterns | 60-sample rolling window |

### The Forensic Risk

**If your device is seized, lost, or forensically examined**, this database reveals:

- **Everywhere you've been** (location tied to every detection)
- **Your daily patterns** (trusted cell tower history shows routine)
- **Networks you've connected to** (WiFi history with locations)
- **Devices near you** (Bluetooth scan results with timestamps)
- **Your cellular provider and SIM** (MCC/MNC, and IMEI/IMSI in OEM mode)

This is exactly the kind of data law enforcement, border agents, or adversaries would want.

### Database Encryption

The app uses SQLCipher with AES-256-GCM encryption:

```
Database: flockyou_database_encrypted
Cipher: AES/GCM/NoPadding (256-bit)
Key Storage: Android Keystore (hardware-backed if available)
```

**Current Implementation**:
- Encryption key generated on first launch
- Key stored in Android Keystore with `setUserAuthenticationRequired(false)`
- Database encrypted at rest
- Key accessible whenever app runs

### Encryption Limitations

| Threat | Protected? | Why |
|--------|------------|-----|
| Casual device theft | ✅ Yes | Database file is encrypted |
| Forensic extraction (locked device) | ✅ Mostly | Requires device unlock or Keystore extraction |
| Forensic extraction (unlocked device) | ❌ No | App can decrypt, so can forensic tools |
| Device seizure with compelled unlock | ❌ No | Once unlocked, data is accessible |
| Cellebrite/GrayKey with device unlocked | ❌ No | Can extract decrypted data |
| Malware on device | ❌ No | Malware runs in same context as app |
| Cloud backup extraction | ⚠️ Partial | Database excluded from backup, but device state isn't |

### Would TPM-Bound Secrets Help?

**Short answer: Marginal improvement, not a solution.**

#### What StrongBox/TPM Could Do

Android devices with hardware security modules (StrongBox on Pixel, Samsung Knox, etc.) can bind keys to:

```kotlin
// Hypothetical TPM-bound key
KeyGenParameterSpec.Builder(...)
    .setIsStrongBoxBacked(true)           // Use dedicated security chip
    .setUserAuthenticationRequired(true)   // Require unlock
    .setUserAuthenticationValidityDurationSeconds(300) // Re-auth every 5 min
    .setUnlockedDeviceRequired(true)       // Only when device unlocked
    .setInvalidatedByBiometricEnrollment(true) // Invalidate if biometrics change
```

#### TPM Binding Analysis

| Scenario | Without TPM | With TPM Binding | Improvement |
|----------|-------------|------------------|-------------|
| Device locked, physical extraction | Key in Keystore | Key in StrongBox | ✅ Harder to extract |
| Device unlocked, forensic tool | App decrypts | App still decrypts | ❌ No change |
| Compelled unlock (legal/coercion) | Data accessible | Data accessible | ❌ No change |
| Chip-off attack | Key extractable | Key hardware-bound | ✅ Significant |
| Cold boot attack | Possible | Mitigated | ✅ Some improvement |
| Malware with root | Full access | Still accessible | ❌ No change |

#### Why TPM Isn't a Complete Solution

1. **The app needs to read the data to function**
   - Detection requires querying historical data (trusted cells, known networks)
   - If the app can read it, forensic tools can too (when device is unlocked)

2. **User authentication window problem**
   - Can't require auth for every database read (scanning happens every 30 seconds)
   - Must cache decryption capability, creating a window of vulnerability

3. **Background service contradiction**
   - App runs as a foreground service continuously
   - Can't require biometric auth while screen is off
   - Service needs database access to detect anomalies

4. **StrongBox availability**
   - Not all devices have StrongBox
   - GrapheneOS supports it, but not all targets do
   - Fallback to software Keystore defeats the purpose

#### What Would Actually Help

| Mitigation | Effectiveness | Trade-off |
|------------|---------------|-----------|
| **Minimal retention** (1-3 days) | ✅ High | Less historical anomaly detection |
| **Manual purge before risk** | ✅ High | Requires user action |
| **Duress password/wipe** | ✅ High | Complex UX, legal implications |
| **No location storage option** | ✅ Medium | Reduced detection capability |
| **Memory-only mode** | ✅ High | No persistence across reboots |
| **TPM + short auth window** | ⚠️ Medium | Battery drain, UX friction |
| **Remote wipe capability** | ⚠️ Medium | Requires network, trust issues |

### Recommended Configurations by Threat Model

#### Journalist/Activist (Seizure Risk)

```
Retention: 1 day
Location storage: Disabled (if option added)
Pre-crossing routine: Clear all data
Consider: Memory-only mode (if implemented)
```

#### Security Researcher (Testing)

```
Retention: 7 days
Full features: Enabled
Export regularly: For analysis
Clear before: Travel, sensitive meetings
```

#### General Privacy User

```
Retention: 30 days (default)
Ultrasonic: Disabled (if not needed)
Location: Keep enabled for anomaly detection
```

#### High-Security Deployment (OEM)

```
Mode: System (not OEM) unless IMSI detection required
Retention: 3 days
SELinux: Custom policy restricting network
Audit: Regular log review
```

### Implementation Recommendations

If you're building a custom ROM with Flock You, consider these enhancements:

#### 1. Add Memory-Only Mode
```kotlin
// Store detections in memory only, never persist to disk
class VolatileDetectionRepository : DetectionRepository {
    private val detections = mutableListOf<Detection>()
    // Data lost on app restart - maximum privacy
}
```

#### 2. Add Location-Free Mode
```kotlin
// Detect without storing coordinates
data class Detection(
    // ... other fields
    val latitude: Double? = null,  // Optional
    val longitude: Double? = null, // Optional
)
```

#### 3. Add Duress Wipe
```kotlin
// Secondary PIN that wipes database
if (enteredPin == duressPin) {
    database.clearAllTables()
    // Optionally: corrupt the encryption key
}
```

#### 4. Reduce Default Retention for OEM
```xml
<!-- In default SharedPreferences for OEM builds -->
<integer name="retention_days">3</integer>
```

### Transparency Summary

**This app collects significant data locally. No encryption scheme fully protects against:**
- Unlocked device forensics
- Compelled cooperation
- Malware with equivalent privileges

**The best protection is minimizing what's stored:**
- Use shortest retention period acceptable for your use case
- Manually clear data before high-risk situations
- Consider whether you need all detection features enabled

## License

This integration guide and associated code is provided under the same license as the main Flock You application.

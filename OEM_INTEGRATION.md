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

For GrapheneOS builds, add the following to your device tree:

### 1. Add to device.mk or similar

```makefile
# Flock You - Surveillance Detection
PRODUCT_PACKAGES += FlockYou

# Permission whitelist
PRODUCT_COPY_FILES += \
    vendor/flockyou/privapp-permissions-flockyou.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-flockyou.xml
```

### 2. Create Android.bp or Android.mk

```makefile
# Android.mk for Flock You
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := FlockYou
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := platform  # or PRESIGNED for custom signature
LOCAL_SRC_FILES := FlockYou.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)

include $(BUILD_PREBUILT)
```

### 3. For OEM Mode (Platform Signing)

Sign the APK with the platform certificate:

```bash
# Using AOSP signing tools
java -jar signapk.jar platform.x509.pem platform.pk8 app-oem-release-unsigned.apk app-oem-release.apk

# Using apksigner
apksigner sign --ks platform.keystore --ks-key-alias platform app-oem-release.apk
```

## CalyxOS / LineageOS Integration

Similar to GrapheneOS, add to your device configuration:

```makefile
# packages/apps/FlockYou/Android.bp
android_app_import {
    name: "FlockYou",
    apk: "FlockYou.apk",
    privileged: true,
    certificate: "platform",
    required: ["privapp-permissions-flockyou.xml"],
}

prebuilt_etc {
    name: "privapp-permissions-flockyou.xml",
    src: "privapp-permissions-flockyou.xml",
    sub_dir: "permissions",
    system_ext_specific: true,
}
```

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

- System/OEM installations should only be done on trusted devices
- The enhanced capabilities provide more surveillance detection but also more device access
- Consider the trust model of your deployment environment
- For maximum security, use the sideload version which has minimal privileged access

## License

This integration guide and associated code is provided under the same license as the main Flock You application.

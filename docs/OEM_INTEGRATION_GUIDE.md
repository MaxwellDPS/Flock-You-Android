# Flock You - OEM Integration Guide

This guide provides comprehensive documentation for OEM partners integrating Flock You into their Android devices or custom ROMs.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Quick Start](#2-quick-start)
3. [Branding Customization](#3-branding-customization)
4. [Build Configuration](#4-build-configuration)
5. [System Integration](#5-system-integration-for-rom-builders)
6. [Feature Configuration](#6-feature-configuration)
7. [Testing](#7-testing)
8. [Checklist for OEM Release](#8-checklist-for-oem-release)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. Overview

### Build Flavors

Flock You supports three build flavors, each designed for different deployment scenarios:

| Flavor | Description | Installation Location | Signing | Privileges |
|--------|-------------|----------------------|---------|------------|
| **sideload** | Standard APK for end users | User space (`/data/app/`) | Any certificate | Standard Android permissions |
| **system** | Privileged system app | `/system_ext/priv-app/` or `/system/priv-app/` | PRESIGNED (your certificate) | Enhanced permissions via whitelist |
| **oem** | Platform-signed OEM app | `/system_ext/priv-app/` | Platform certificate | Maximum privileges |

### Privilege Levels and Capabilities

| Capability | Sideload | System | OEM |
|------------|----------|--------|-----|
| WiFi Scanning | Subject to throttling (4 scans/2min) | Can disable throttling | Can disable throttling |
| BLE Scanning | Duty-cycled by OS | Continuous (with whitelist) | Continuous |
| MAC Address Access | Randomized only | Real hardware addresses | Real hardware addresses |
| Phone State (IMEI/IMSI) | Limited | Limited | Full access |
| Process Persistence | Standard (may be killed) | Persistent | Persistent |
| Background Start | Restricted | Allowed | Allowed |

### Target Audience

This guide is intended for:
- OEM engineering teams integrating the app into device firmware
- Custom ROM developers (GrapheneOS, CalyxOS, LineageOS, etc.)
- Enterprise mobility teams deploying to managed devices
- Security-focused hardware vendors

---

## 2. Quick Start

### Building an OEM Variant

**Prerequisites:**
- JDK 17
- Android SDK with API 34
- Gradle 8.x

**Build Commands:**

```bash
# Debug build (for testing)
./gradlew assembleOemDebug

# Release build (for production)
./gradlew assembleOemRelease

# All variants at once
./gradlew assembleOem
```

**Output locations:**
- Debug: `app/build/outputs/apk/oem/debug/app-oem-debug.apk`
- Release: `app/build/outputs/apk/oem/release/app-oem-release.apk`

### Minimum Required Customizations

For a basic OEM integration, you must:

1. **Update the app name** in `app/src/main/res/values/strings.xml`:
   ```xml
   <string name="app_name_oem">Your Brand Scanner</string>
   ```

2. **Replace launcher icons** in `app/src/main/res/mipmap-*` directories

3. **Sign the APK** with your platform certificate or your own release key

4. **Include permission whitelists** (see [System Integration](#5-system-integration-for-rom-builders))

---

## 3. Branding Customization

### Changing the App Name

The app name is controlled via string resources. For OEM builds, override these in a flavor-specific resource directory.

**Option 1: Override in main strings.xml**

Edit `app/src/main/res/values/strings.xml`:
```xml
<resources>
    <!-- Default app names -->
    <string name="app_name">Flock You</string>
    <string name="app_name_system">Flock You (System)</string>
    <string name="app_name_oem">Your Brand Scanner</string>  <!-- Change this -->

    <!-- App description shown in About screen -->
    <string name="app_description">Surveillance detection app</string>
</resources>
```

**Option 2: Create flavor-specific resources (recommended)**

Create: `app/src/oem/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Your Brand Scanner</string>
    <string name="app_name_oem">Your Brand Scanner</string>
    <string name="app_description">Device security scanner</string>

    <!-- Customize notification channel -->
    <string name="notification_channel_name">Security Scanning</string>
    <string name="notification_channel_description">Background security scanning notifications</string>
</resources>
```

### Changing Colors

**File:** `app/src/main/res/values/colors.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Primary brand color -->
    <color name="primary">#00E676</color>

    <!-- Background colors -->
    <color name="ic_launcher_background">#0D1117</color>
    <color name="background">#0D1117</color>
    <color name="surface">#161B22</color>

    <!-- Threat level colors (keep consistent for UX) -->
    <color name="threat_critical">#FF1744</color>
    <color name="threat_high">#FF5722</color>
    <color name="threat_medium">#FFB300</color>
    <color name="threat_low">#8BC34A</color>
    <color name="threat_info">#64B5F6</color>
</resources>
```

For flavor-specific colors, create: `app/src/oem/res/values/colors.xml`

### Replacing Launcher Icons

**Adaptive Icons (Android 8.0+):**

| File | Purpose |
|------|---------|
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Vector foreground |
| `app/src/main/res/values/colors.xml` → `ic_launcher_background` | Background color |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon definition |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Round variant |

**Legacy Icons (fallback):**

Place PNG files in:
- `app/src/main/res/mipmap-mdpi/` (48x48)
- `app/src/main/res/mipmap-hdpi/` (72x72)
- `app/src/main/res/mipmap-xhdpi/` (96x96)
- `app/src/main/res/mipmap-xxhdpi/` (144x144)
- `app/src/main/res/mipmap-xxxhdpi/` (192x192)

### Customizing Notification Icons

Notification small icons must be:
- Monochrome (white with transparency)
- 24dp x 24dp vector or appropriately sized PNGs

Place in: `app/src/main/res/drawable/ic_notification.xml`

---

## 4. Build Configuration

### gradle.properties Options

**File:** `gradle.properties`

```properties
# Memory allocation for Gradle daemon
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC -Dfile.encoding=UTF-8

# Kotlin daemon settings
kotlin.daemon.jvmargs=-Xmx4g -XX:+UseParallelGC

# Enable AndroidX
android.useAndroidX=true

# Kotlin code style
kotlin.code.style=official

# Non-transitive R classes (reduces APK size)
android.nonTransitiveRClass=true

# Skip Flipper FAP build (if not using Flipper integration)
skipFlipperBuild=true
```

**Environment variables for CI:**
```bash
# Skip Flipper build in CI (no ufbt installed)
export SKIP_FLIPPER_BUILD=true
```

### Application ID Customization

In `app/build.gradle.kts`, the OEM flavor can customize the application ID:

```kotlin
productFlavors {
    create("oem") {
        dimension = "installMode"
        // Change application ID for your OEM package
        applicationId = "com.yourbrand.securityscanner"
        // Or use suffix: applicationIdSuffix = ".oem"

        versionNameSuffix = "-oem"

        buildConfigField("boolean", "IS_SYSTEM_BUILD", "true")
        buildConfigField("boolean", "IS_OEM_BUILD", "true")
        buildConfigField("String", "BUILD_MODE", "\"oem\"")

        manifestPlaceholders["appLabel"] = "@string/app_name_oem"
    }
}
```

### Version Naming Conventions

Current configuration in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 1
    versionName = "1.0.0"
}
```

**Recommended OEM versioning:**
- Format: `MAJOR.MINOR.PATCH-oem.BUILD`
- Example: `1.2.0-oem.123`

### Feature Flags (BuildConfig)

The following flags are available at runtime via `BuildConfig`:

| Flag | Type | Description |
|------|------|-------------|
| `BUILD_MODE` | String | `"sideload"`, `"system"`, or `"oem"` |
| `IS_SYSTEM_BUILD` | Boolean | `true` for system and OEM builds |
| `IS_OEM_BUILD` | Boolean | `true` only for OEM builds |
| `DEBUG` | Boolean | `true` for debug builds |
| `APPLICATION_ID` | String | Package name |
| `VERSION_NAME` | String | Version string |
| `VERSION_CODE` | Int | Version code |

**Usage in code:**
```kotlin
if (BuildConfig.IS_OEM_BUILD) {
    // OEM-specific behavior
}

when (BuildConfig.BUILD_MODE) {
    "sideload" -> { /* Standard user behavior */ }
    "system" -> { /* System app behavior */ }
    "oem" -> { /* Full OEM privileges */ }
}
```

---

## 5. System Integration (for ROM Builders)

### Directory Structure

```
system/
├── Android.bp              # Soong build configuration
├── Android.mk              # Legacy make configuration
├── flockyou.mk            # Product makefile include
├── privapp-permissions-flockyou.xml
└── default-permissions-flockyou.xml
```

### Android.bp Configuration (Soong)

**File:** `system/Android.bp`

```blueprint
// Flock You - Android.bp for AOSP build integration
android_app_import {
    name: "FlockYou",
    apk: "FlockYou.apk",

    // Install as privileged app in /system_ext/priv-app/
    privileged: true,
    system_ext_specific: true,

    // Signing configuration:
    // - Use "platform" for OEM builds (maximum privileges)
    // - Use "PRESIGNED" if APK is already signed
    certificate: "platform",

    // DEX optimization
    dex_preopt: {
        enabled: true,
    },

    // Required permission whitelists
    required: [
        "privapp_permissions_flockyou",
        "default_permissions_flockyou",
    ],
}

// Privileged permission whitelist
prebuilt_etc {
    name: "privapp_permissions_flockyou",
    src: "privapp-permissions-flockyou.xml",
    sub_dir: "permissions",
    system_ext_specific: true,
    filename: "privapp-permissions-flockyou.xml",
}

// Default runtime permissions
prebuilt_etc {
    name: "default_permissions_flockyou",
    src: "default-permissions-flockyou.xml",
    sub_dir: "default-permissions",
    system_ext_specific: true,
    filename: "default-permissions-flockyou.xml",
}
```

### Android.mk Configuration (Legacy)

**File:** `system/Android.mk`

```makefile
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := FlockYou
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_PRIVILEGED_MODULE := true
LOCAL_SYSTEM_EXT_MODULE := true
LOCAL_CERTIFICATE := platform
LOCAL_SRC_FILES := FlockYou.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_DEX_PREOPT := true
LOCAL_REQUIRED_MODULES := \
    privapp-permissions-flockyou.xml \
    default-permissions-flockyou.xml
LOCAL_OVERRIDES_PACKAGES := FlockYou
include $(BUILD_PREBUILT)

# Permission whitelist
include $(CLEAR_VARS)
LOCAL_MODULE := privapp-permissions-flockyou.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SYSTEM_EXT_MODULE := true
LOCAL_MODULE_PATH := $(TARGET_OUT_SYSTEM_EXT_ETC)/permissions
LOCAL_SRC_FILES := privapp-permissions-flockyou.xml
include $(BUILD_PREBUILT)

# Default permissions
include $(CLEAR_VARS)
LOCAL_MODULE := default-permissions-flockyou.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_SYSTEM_EXT_MODULE := true
LOCAL_MODULE_PATH := $(TARGET_OUT_SYSTEM_EXT_ETC)/default-permissions
LOCAL_SRC_FILES := default-permissions-flockyou.xml
include $(BUILD_PREBUILT)
```

### Privileged Permissions Setup

**File:** `system/privapp-permissions-flockyou.xml`

This file whitelists privileged permissions for system/OEM installations:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.flockyou">
        <!-- BLE: Bypass duty cycling for continuous scanning -->
        <permission name="android.permission.BLUETOOTH_PRIVILEGED"/>

        <!-- MAC: Access real hardware addresses -->
        <permission name="android.permission.PEERS_MAC_ADDRESS"/>
        <permission name="android.permission.LOCAL_MAC_ADDRESS"/>

        <!-- Phone: Access IMEI/IMSI for IMSI catcher detection -->
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>

        <!-- Network: Control WiFi scan throttling -->
        <permission name="android.permission.CONNECTIVITY_INTERNAL"/>
        <permission name="android.permission.NETWORK_SETTINGS"/>

        <!-- Process: Keep service running persistently -->
        <permission name="android.permission.PERSISTENT_ACTIVITY"/>
        <permission name="android.permission.START_ACTIVITIES_FROM_BACKGROUND"/>

        <!-- Multi-user support -->
        <permission name="android.permission.INTERACT_ACROSS_USERS"/>
        <permission name="android.permission.MANAGE_USERS"/>

        <!-- Usage stats for battery optimization -->
        <permission name="android.permission.PACKAGE_USAGE_STATS"/>
    </privapp-permissions>
</permissions>
```

**Installation paths:**
- Android 11+: `/system_ext/etc/permissions/privapp-permissions-flockyou.xml`
- Android 10: `/system/etc/permissions/privapp-permissions-flockyou.xml`

### Default Permissions Setup

**File:** `system/default-permissions-flockyou.xml`

Pre-grants runtime permissions on first boot:

```xml
<?xml version="1.0" encoding="utf-8"?>
<exceptions>
    <exception package="com.flockyou">
        <!-- Location for WiFi/cellular scanning -->
        <permission name="android.permission.ACCESS_FINE_LOCATION" fixed="false"/>
        <permission name="android.permission.ACCESS_COARSE_LOCATION" fixed="false"/>
        <permission name="android.permission.ACCESS_BACKGROUND_LOCATION" fixed="false"/>

        <!-- Bluetooth for BLE scanning -->
        <permission name="android.permission.BLUETOOTH_SCAN" fixed="false"/>
        <permission name="android.permission.BLUETOOTH_CONNECT" fixed="false"/>

        <!-- Phone state for cellular monitoring -->
        <permission name="android.permission.READ_PHONE_STATE" fixed="false"/>

        <!-- Notifications -->
        <permission name="android.permission.POST_NOTIFICATIONS" fixed="false"/>

        <!-- Nearby devices (Android 12+) -->
        <permission name="android.permission.NEARBY_WIFI_DEVICES" fixed="false"/>
    </exception>
</exceptions>
```

**Installation path:** `/system_ext/etc/default-permissions/default-permissions-flockyou.xml`

### Platform Signing vs Pre-signed Options

| Option | Certificate | Privileges | Use Case |
|--------|-------------|------------|----------|
| Platform | `certificate: "platform"` | Maximum | OEM-embedded builds |
| Pre-signed | `certificate: "PRESIGNED"` | Whitelist only | Third-party system apps |

**For pre-signed APKs**, add to `Android.bp`:
```blueprint
android_app_import {
    name: "FlockYou",
    apk: "FlockYou.apk",
    privileged: true,
    certificate: "PRESIGNED",
    presigned: true,
    // ... rest of config
}
```

### Adding to Your Build

**In your device.mk or vendor configuration:**

```makefile
# Include Flock You
$(call inherit-product, vendor/flockyou/flockyou.mk)

# Or add directly to PRODUCT_PACKAGES
PRODUCT_PACKAGES += FlockYou
```

---

## 6. Feature Configuration

### Configurable Features

All features are controlled via `PrivacySettings` (stored in DataStore):

| Feature | Default (Sideload) | Default (OEM) | Config Key |
|---------|-------------------|---------------|------------|
| Store Location | ON | OFF | `storeLocationWithDetections` |
| Ephemeral Mode | OFF | OFF | `ephemeralModeEnabled` |
| Auto-Purge on Lock | OFF | OFF | `autoPurgeOnScreenLock` |
| Ultrasonic Detection | OFF | OFF | `ultrasonicDetectionEnabled` |
| Data Retention | 3 days | 3 days | `retentionPeriod` |
| Quick Wipe Confirmation | ON | ON | `quickWipeRequiresConfirmation` |

### Disabling Specific Features

**Flipper Zero Integration:**

To disable Flipper integration entirely, skip the FAP build:

```bash
# Environment variable
export SKIP_FLIPPER_BUILD=true

# Or gradle property
./gradlew assembleOemRelease -PskipFlipperBuild=true
```

**Ultrasonic Detection:**

Ultrasonic detection is opt-in by default. To prevent users from enabling it:

1. Remove the microphone permission from the manifest (not recommended)
2. Or hide the UI toggle via a custom build flag

**Cellular/IMSI Catcher Detection:**

This requires READ_PHONE_STATE permission. Without privileged permissions, functionality is limited.

### Privacy Defaults for OEM Builds

OEM builds automatically apply privacy-focused defaults:

```kotlin
// In PrivacySettings.kt
data class PrivacySettings(
    val storeLocationWithDetections: Boolean = !BuildConfig.IS_OEM_BUILD,
    // ... other settings
)
```

To customize defaults, modify `app/src/main/java/com/flockyou/data/PrivacySettings.kt`.

### Retention Period Options

Available retention periods:

| Period | Hours | Display Name |
|--------|-------|--------------|
| FOUR_HOURS | 4 | "4 hours" |
| ONE_DAY | 24 | "1 day" |
| THREE_DAYS | 72 | "3 days" |
| SEVEN_DAYS | 168 | "7 days" |
| THIRTY_DAYS | 720 | "30 days" |

---

## 7. Testing

### Running OEM E2E Tests

The project includes comprehensive OEM readiness tests:

```bash
# Run all OEM tests on connected device
./gradlew connectedOemDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemReadinessE2ETest

# Run specific test suites
./gradlew connectedOemDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemBrandingE2ETest

./gradlew connectedOemDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemConfigurationE2ETest

./gradlew connectedOemDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemFeatureTogglesE2ETest
```

### What the Tests Validate

**OemReadinessE2ETest:**
- Build variant configuration consistency
- Branding resource availability
- Privacy defaults per build type
- Privilege mode detection
- White-label completeness
- Multi-tenant data isolation
- Deployment flexibility

**OemBrandingE2ETest:**
- App name matches build variant
- Theme colors are accessible
- Launcher icons exist
- All critical strings are externalizable
- No hardcoded brand references

**OemConfigurationE2ETest:**
- Privacy defaults vary by build
- Data retention is configurable
- Feature toggles work correctly
- No hardcoded API endpoints
- Database encryption is enforced

**OemFeatureTogglesE2ETest:**
- Detection features can be toggled
- Privacy features are configurable
- Security features are available
- Privilege-based features detect correctly
- BuildConfig fields are stable API

### CI/CD Integration Examples

**GitHub Actions (existing workflow):**

```yaml
# .github/workflows/android-ci.yml already includes:
- Build jobs for all flavors (oem, sideload, system)
- Unit test jobs per flavor
- Release builds with signing options
- SLSA attestation

# Run OEM tests in CI
- name: Run OEM E2E Tests
  run: |
    ./gradlew connectedOemDebugAndroidTest \
      --stacktrace
```

**Custom CI Pipeline:**

```bash
#!/bin/bash
# oem-ci.sh

# 1. Build OEM variant
./gradlew assembleOemRelease -PskipFlipperBuild=true

# 2. Run unit tests
./gradlew testOemReleaseUnitTest

# 3. Run lint
./gradlew lintOemRelease

# 4. Run instrumented tests (requires emulator/device)
./gradlew connectedOemDebugAndroidTest

# 5. Generate test report
./gradlew jacocoTestReportOem
```

### Test Reports

Test reports are generated in:
- Unit tests: `app/build/reports/tests/testOemDebugUnitTest/`
- Instrumented tests: `app/build/reports/androidTests/connected/`
- Lint: `app/build/reports/lint-results-oemDebug.html`

---

## 8. Checklist for OEM Release

### Pre-Release Verification Steps

- [ ] **Build Configuration**
  - [ ] Verify `BUILD_MODE` is set to `"oem"`
  - [ ] Confirm `IS_OEM_BUILD` is `true`
  - [ ] Check version name and code are correct
  - [ ] Validate application ID matches your package name

- [ ] **Branding**
  - [ ] App name customized in strings.xml
  - [ ] App description updated
  - [ ] Notification channel names customized
  - [ ] Launcher icons replaced (all densities)
  - [ ] Theme colors match brand guidelines

- [ ] **Signing**
  - [ ] APK signed with platform certificate (for full OEM) OR
  - [ ] APK signed with your release key (for pre-signed)
  - [ ] Signature verified: `apksigner verify --print-certs app.apk`

- [ ] **Permissions**
  - [ ] `privapp-permissions-flockyou.xml` included in ROM
  - [ ] `default-permissions-flockyou.xml` included in ROM
  - [ ] Package name matches in permission files

- [ ] **Testing**
  - [ ] All unit tests pass
  - [ ] OEM E2E tests pass
  - [ ] Manual testing on target device
  - [ ] Privilege mode detected correctly at runtime

- [ ] **Privacy**
  - [ ] Location storage default is OFF for OEM
  - [ ] No analytics or telemetry endpoints configured
  - [ ] Database encryption is enforced

### Required Customizations

1. **App name** (`app_name_oem` in strings.xml)
2. **Application ID** (if different from `com.flockyou`)
3. **Launcher icons** (mipmap directories)
4. **Signing certificate** (platform or release key)
5. **Permission whitelist files** (for system/priv-app)

### Optional Customizations

- Custom theme colors
- Custom notification icons
- Modified privacy defaults
- Disabled features (Flipper, ultrasonic)
- Custom retention period defaults
- Localized strings for additional languages

---

## 9. Troubleshooting

### Common Issues and Solutions

#### Issue: App crashes on launch with "SecurityException"

**Cause:** Privileged permissions not whitelisted.

**Solution:**
1. Verify `privapp-permissions-flockyou.xml` is in correct location
2. Check package name matches in XML file
3. Rebuild ROM and reflash

```bash
# Verify permission file exists
adb shell cat /system_ext/etc/permissions/privapp-permissions-flockyou.xml
```

#### Issue: WiFi scan throttling still active

**Cause:** CONNECTIVITY_INTERNAL permission not granted.

**Solution:**
1. Ensure permission is in whitelist
2. Verify app is in `/system_ext/priv-app/` (not `/system/app/`)
3. Check with:
```bash
adb shell dumpsys package com.flockyou | grep "CONNECTIVITY_INTERNAL"
```

#### Issue: BuildConfig.IS_OEM_BUILD is false

**Cause:** Wrong build variant selected.

**Solution:**
```bash
# Ensure you're building OEM variant
./gradlew assembleOemRelease

# NOT sideloadRelease or systemRelease
```

#### Issue: App name shows "Flock You" instead of custom name

**Cause:** String resources not overridden correctly.

**Solution:**
1. Verify flavor-specific resources in `app/src/oem/res/values/`
2. Check manifest placeholder:
```kotlin
manifestPlaceholders["appLabel"] = "@string/app_name_oem"
```
3. Clean and rebuild:
```bash
./gradlew clean assembleOemRelease
```

#### Issue: Tests fail with "Privilege mode mismatch"

**Cause:** Running on non-system partition without privileged permissions.

**Solution:**
1. Install app in system partition for accurate testing
2. Or accept that sideload mode will be detected in test environment

#### Issue: Flipper FAP not building

**Cause:** ufbt not installed or SKIP_FLIPPER_BUILD set.

**Solution:**
```bash
# Install ufbt
pip install ufbt

# Or skip Flipper build if not needed
./gradlew assembleOemRelease -PskipFlipperBuild=true
```

#### Issue: DEX optimization fails during ROM build

**Cause:** Incompatible dex2oat flags.

**Solution:** Disable DEX preopt for debugging:
```blueprint
dex_preopt: {
    enabled: false,
},
```

### Contact Information

For OEM integration support:

- **GitHub Issues:** [Repository Issues Page]
- **Security Issues:** Report privately via GitHub Security Advisories
- **Documentation Updates:** Submit PR to this repository

### Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2025-01 | Initial OEM integration guide |

---

## Appendix: File Reference

### Key Files for OEM Customization

| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | Build configuration, flavors, signing |
| `gradle.properties` | Gradle settings, memory allocation |
| `app/src/main/res/values/strings.xml` | App strings, names, descriptions |
| `app/src/main/res/values/colors.xml` | Theme colors |
| `app/src/main/res/mipmap-*/` | Launcher icons |
| `app/src/main/AndroidManifest.xml` | Permissions, components |
| `app/src/main/java/com/flockyou/data/PrivacySettings.kt` | Default privacy settings |
| `system/Android.bp` | Soong build configuration |
| `system/Android.mk` | Make build configuration |
| `system/privapp-permissions-flockyou.xml` | Privileged permission whitelist |
| `system/default-permissions-flockyou.xml` | Pre-granted runtime permissions |

### Test Files

| File | Purpose |
|------|---------|
| `app/src/androidTest/java/com/flockyou/oem/OemReadinessE2ETest.kt` | OEM readiness validation |
| `app/src/androidTest/java/com/flockyou/oem/OemBrandingE2ETest.kt` | Branding customization tests |
| `app/src/androidTest/java/com/flockyou/oem/OemConfigurationE2ETest.kt` | Configuration flexibility tests |
| `app/src/androidTest/java/com/flockyou/oem/OemFeatureTogglesE2ETest.kt` | Feature toggle tests |

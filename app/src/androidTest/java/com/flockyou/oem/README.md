# OEM Readiness E2E Test Suite

This directory contains comprehensive end-to-end tests that validate the application's OEM readiness and white-label capabilities. These tests ensure the app meets enterprise deployment requirements and can be successfully integrated by OEM partners.

## Overview

The OEM test suite covers six critical areas:

1. **OemReadinessE2ETest** - Overall OEM partnership requirements
2. **OemBrandingE2ETest** - Theme colors, logos, app name customization
3. **OemConfigurationE2ETest** - Configuration flexibility and externalization
4. **OemBuildVariantsE2ETest** - Build variant correctness (sideload/system/OEM)
5. **OemResourceIsolationE2ETest** - Multi-tenant data isolation
6. **OemFeatureTogglesE2ETest** - Feature toggles and API configuration

## Test Coverage Summary

### 1. Branding Customization (OemBrandingE2ETest)

Validates that all visual branding elements can be customized without code changes:

- App name variants per build (sideload, system, OEM)
- Theme colors are configurable via resources
- Launcher icons and notification icons are replaceable
- All critical strings are externalized in resources
- No hardcoded brand references outside resources
- Support for light and dark themes
- Capability and privilege mode display strings
- Threat level and device type branding

**Key Tests:**
- `branding_appNameMatchesBuildVariant()` - Verifies correct name per build
- `branding_themeColorsAreAccessible()` - Validates theme customization
- `branding_launcherIconExists()` - Ensures icon resources are present
- `branding_allCriticalStringsAreInResources()` - No hardcoded UI text

### 2. Configuration Flexibility (OemConfigurationE2ETest)

Validates that critical application settings can be configured per OEM:

- Privacy settings have OEM-aware defaults
- Data retention policies are flexible
- Feature flags can be controlled per OEM
- No hardcoded API keys or endpoints
- Build mode determines default configurations
- Notification channels are customizable
- Database encryption is enforced

**Key Tests:**
- `config_privacyDefaultsVaryByBuild()` - Build-specific defaults
- `config_retentionPeriodsAreFlexible()` - Configurable retention
- `config_noHardcodedApiKeys()` - No embedded secrets
- `config_oemBuildHasPrivacyFocusedDefaults()` - OEM privacy settings

### 3. Build Variants (OemBuildVariantsE2ETest)

Validates that different build variants produce correctly configured builds:

- Application IDs are correct per variant
- Version names include appropriate suffixes
- Privilege detection works correctly per variant
- Permissions are declared appropriately
- Capabilities match expected privilege level
- BuildConfig flags are consistent

**Key Tests:**
- `buildVariant_hasCorrectApplicationId()` - Package naming
- `flavor_sideloadConfigurationIsCorrect()` - Sideload build validation
- `flavor_systemConfigurationIsCorrect()` - System build validation
- `flavor_oemConfigurationIsCorrect()` - OEM build validation
- `privilege_capabilitiesMatchMode()` - Runtime capability detection

### 4. Resource Isolation (OemResourceIsolationE2ETest)

Validates proper resource isolation for multi-tenant security:

- Database files are app-private and encrypted
- SharedPreferences are not in external storage
- DataStore is properly isolated
- Cache directories are app-private
- No world-readable or world-writable files
- Content providers are not exported
- Each OEM build has unique package name

**Key Tests:**
- `isolation_databaseIsAppPrivate()` - Database isolation
- `isolation_sharedPreferencesAreAppPrivate()` - Preferences isolation
- `isolation_noDataInExternalFiles()` - No external storage use
- `isolation_contentProvidersAreNotExported()` - No data leakage
- `isolation_multipleOemBuildsCanCoexist()` - Multi-tenant support

### 5. Feature Toggles (OemFeatureTogglesE2ETest)

Validates that features can be enabled/disabled per OEM:

- Detection features are toggleable
- Privacy features are configurable
- Security features can be enabled/disabled
- API endpoints are externalized
- Privilege-based features are correctly gated
- No hardcoded analytics/telemetry

**Key Tests:**
- `featureToggle_ultrasonicDetectionIsConfigurable()` - Optional ultrasonic
- `featureToggle_locationStorageIsConfigurable()` - Privacy controls
- `featureToggle_nukeFeatureCanBeEnabled()` - Security features
- `apiConfig_noHardcodedEndpoints()` - Configurable APIs
- `featureToggle_privilegedPhoneAccessRequiresOem()` - Privilege gating

### 6. Overall OEM Readiness (OemReadinessE2ETest)

Comprehensive validation of OEM partnership requirements:

- Build variant configuration
- Branding and white-labeling
- Configuration externalization
- Multi-tenant support
- Deployment flexibility
- Documentation completeness
- No telemetry or hardcoded analytics

**Key Tests:**
- All the original comprehensive OEM readiness tests
- Integration tests combining multiple aspects
- Validation of OEM partner requirements

## Running the Tests

### Run All OEM Tests

```bash
# Run all OEM tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.flockyou.oem
```

### Run Specific Test Suite

```bash
# Branding tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemBrandingE2ETest

# Configuration tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemConfigurationE2ETest

# Build variant tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemBuildVariantsE2ETest

# Resource isolation tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemResourceIsolationE2ETest

# Feature toggle tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemFeatureTogglesE2ETest

# Overall readiness tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemReadinessE2ETest
```

### Run Tests for Specific Build Flavor

```bash
# Test sideload build
./gradlew connectedSideloadDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.flockyou.oem

# Test system build
./gradlew connectedSystemDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.flockyou.oem

# Test OEM build
./gradlew connectedOemDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.flockyou.oem
```

## OEM Integration Checklist

Use this checklist when validating a build for OEM deployment:

### Branding
- [ ] App name can be changed via `strings.xml`
- [ ] App icon can be replaced in `mipmap-*` directories
- [ ] Theme colors can be customized in `colors.xml`
- [ ] All UI text is in string resources (no hardcoded text)
- [ ] No references to original brand in code

### Configuration
- [ ] Privacy defaults match OEM requirements
- [ ] Data retention period is appropriate
- [ ] Feature toggles are set correctly
- [ ] No hardcoded API keys or endpoints
- [ ] Notification settings are customized

### Build Variants
- [ ] Correct build variant is selected (sideload/system/OEM)
- [ ] Application ID is correct for OEM
- [ ] Version name includes appropriate suffix
- [ ] BuildConfig flags match build mode
- [ ] Privilege detection works correctly

### Resource Isolation
- [ ] Database is encrypted and app-private
- [ ] No data stored in external storage
- [ ] SharedPreferences are isolated
- [ ] No exported content providers
- [ ] Package name is unique per OEM

### Feature Toggles
- [ ] All detection features work correctly
- [ ] Privacy features are configurable
- [ ] Security features can be enabled
- [ ] No unexpected API calls
- [ ] Feature flags are accessible

### Security
- [ ] Database encryption is enforced
- [ ] No world-readable files
- [ ] Permissions are properly declared
- [ ] No telemetry or analytics endpoints
- [ ] SLSA attestation is verified

## OEM Customization Guide

### 1. Customize App Name

Edit `app/src/main/res/values/strings.xml`:

```xml
<string name="app_name">YourBrand Detector</string>
<string name="app_name_system">YourBrand Detector (System)</string>
<string name="app_name_oem">YourBrand Detector (OEM)</string>
<string name="app_description">Your description</string>
```

### 2. Customize Theme Colors

Create `app/src/oem/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#YOUR_COLOR</color>
    <color name="primary_dark">#YOUR_COLOR</color>
    <color name="accent">#YOUR_COLOR</color>
</resources>
```

### 3. Replace App Icon

Replace files in `app/src/oem/res/mipmap-*/`:
- `ic_launcher.png` - Square icon
- `ic_launcher_round.png` - Round icon
- `ic_launcher_foreground.png` - Adaptive icon foreground
- `ic_launcher_background.png` - Adaptive icon background

### 4. Configure Privacy Defaults

Edit `app/src/oem/java/.../PrivacySettings.kt`:

```kotlin
data class PrivacySettings(
    val storeLocationWithDetections: Boolean = false, // OEM default: OFF
    val retentionPeriod: RetentionPeriod = RetentionPeriod.THREE_DAYS,
    val ephemeralMode: Boolean = false,
    // ... other settings
)
```

### 5. Set API Endpoints

Edit `app/build.gradle.kts` under OEM flavor:

```kotlin
create("oem") {
    dimension = "installMode"
    buildConfigField("String", "API_ENDPOINT", "\"https://your-api.example.com\"")
    buildConfigField("String", "API_KEY", "\"\"") // Injected via CI/CD
}
```

### 6. Configure Feature Toggles

Edit build configuration or use runtime settings:

```kotlin
// In PrivacySettings or similar
val ultrasonicDetectionEnabled: Boolean = false // Disable per OEM
val locationStorageEnabled: Boolean = false // Privacy-focused
```

## Test Execution Requirements

### Device/Emulator Requirements

- Android API 26 (Android 8.0) or higher
- Supports all architectures (arm64-v8a, armeabi-v7a, x86, x86_64)
- For system/OEM tests: rooted device or emulator with system partition access

### Permissions Required

Tests require the following permissions to be granted:
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+)

### Test Environment

- Tests run in isolation with clean app data
- Each test class uses `@Before` to set up clean state
- Tests do not require network connectivity
- Tests do not require external services

## Continuous Integration

### GitHub Actions Example

```yaml
name: OEM Tests

on:
  pull_request:
    branches: [ main ]
  push:
    branches: [ main ]

jobs:
  test-oem:
    runs-on: macos-latest
    strategy:
      matrix:
        flavor: [sideload, system, oem]
        api-level: [26, 29, 33, 34]

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Run OEM tests for ${{ matrix.flavor }}
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api-level }}
          target: default
          arch: x86_64
          script: ./gradlew connected${{ matrix.flavor }}DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.flockyou.oem

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-reports-${{ matrix.flavor }}-api${{ matrix.api-level }}
          path: app/build/reports/androidTests/
```

## Troubleshooting

### Tests Fail with "Permission Denied"

**Cause:** App doesn't have required permissions.

**Solution:** Grant permissions manually or use `adb shell pm grant`:

```bash
adb shell pm grant com.flockyou android.permission.BLUETOOTH_SCAN
adb shell pm grant com.flockyou android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.flockyou android.permission.POST_NOTIFICATIONS
```

### Tests Fail with "Database Locked"

**Cause:** Previous test didn't clean up database connection.

**Solution:** Ensure `TestHelpers.clearAppData()` is called in `@Before` methods.

### Tests Fail on System/OEM Builds

**Cause:** App is not installed as system app or not platform-signed.

**Solution:** These tests document expected behavior. In test environment, some checks may be skipped.

### Resource Not Found Errors

**Cause:** Resources not defined in test variant.

**Solution:** Ensure all required string resources exist in `values/strings.xml`.

## Best Practices

1. **Run all OEM tests before release** - Ensure OEM readiness for every release
2. **Test all build variants** - Sideload, system, and OEM builds must all pass
3. **Verify on multiple Android versions** - Test on API 26, 29, 33, and 34
4. **Check privilege detection** - Ensure runtime detection matches build mode
5. **Validate isolation** - Confirm no data leakage between OEM builds
6. **Document customizations** - Track all OEM-specific changes

## Additional Resources

- **OEM Integration Guide**: `/OEM_INTEGRATION.md`
- **Build Configuration**: `/app/build.gradle.kts`
- **System Integration Files**: `/system/` directory
- **Test Utilities**: `/app/src/androidTest/java/com/flockyou/utils/`

## Contributing

When adding new OEM-related features:

1. Add corresponding E2E tests to appropriate test class
2. Ensure feature is configurable per OEM (no hardcoding)
3. Document feature in OEM_INTEGRATION.md
4. Update this README with new test coverage
5. Verify all OEM tests still pass

## Contact

For questions about OEM integration or test failures:
- Review existing tests for examples
- Check OEM_INTEGRATION.md for configuration details
- File an issue with test output and device details

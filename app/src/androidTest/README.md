# Flock You Android - Comprehensive E2E Test Suite

This directory contains comprehensive end-to-end (E2E) tests for the Flock You Android application, with a focus on OEM readiness, security features, privacy controls, and detection capabilities.

## Overview

The test suite validates all critical functionality required for production deployment and OEM partnerships:

- **OEM Readiness**: Build variants, branding, configuration, white-labeling
- **Security Features**: Nuke/wipe, duress PIN, failed auth, secure memory
- **Privacy Features**: Ephemeral mode, data retention, location controls
- **Detection System**: All protocols (WiFi, Cellular, BLE, Ultrasonic, Satellite, RF)
- **Data Management**: Encryption, export, repositories, integrity
- **UI Journeys**: Navigation, authentication, user flows
- **Service Lifecycle**: Background operation, receivers, workers

## Test Structure

```
androidTest/
├── java/com/flockyou/
│   ├── oem/
│   │   └── OemReadinessE2ETest.kt         # OEM features & white-labeling
│   ├── security/
│   │   └── SecurityFeaturesE2ETest.kt     # Nuke, duress PIN, secure memory
│   ├── privacy/
│   │   └── PrivacyFeaturesE2ETest.kt      # Ephemeral mode, retention, consent
│   ├── detection/
│   │   └── DetectionSystemE2ETest.kt      # All detection protocols & methods
│   ├── data/
│   │   └── DataManagementE2ETest.kt       # Database, export, repositories
│   └── utils/
│       ├── TestDataFactory.kt             # Test data generation
│       └── TestHelpers.kt                 # Common test utilities
└── README.md (this file)
```

## Running Tests

### Run All E2E Tests
```bash
./gradlew connectedAndroidTest
```

### Run Specific Test Suite
```bash
# OEM readiness tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.oem.OemReadinessE2ETest

# Security tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.security.SecurityFeaturesE2ETest

# Privacy tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.privacy.PrivacyFeaturesE2ETest

# Detection system tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.detection.DetectionSystemE2ETest

# Data management tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flockyou.data.DataManagementE2ETest
```

### Run Tests for Specific Build Flavor
```bash
# Test sideload build
./gradlew connectedSideloadDebugAndroidTest

# Test system build
./gradlew connectedSystemDebugAndroidTest

# Test OEM build
./gradlew connectedOemDebugAndroidTest
```

## Test Coverage

### 1. OEM Readiness Tests (OemReadinessE2ETest)

**Build Variant Configuration**
- ✓ Build flavors are correctly configured (sideload, system, OEM)
- ✓ BuildConfig flags are consistent with build mode
- ✓ OEM builds have enhanced capabilities

**Branding Customization**
- ✓ All app name variants exist (standard, system, OEM)
- ✓ Build mode description strings are defined
- ✓ Theme colors and resources are configurable
- ✓ No hardcoded brand references outside resources

**Configuration Externalization**
- ✓ Privacy settings have OEM-aware defaults
- ✓ Retention periods are flexible
- ✓ Notification settings are customizable
- ✓ All strings are externalizable

**White-Label Completeness**
- ✓ No hardcoded API keys
- ✓ Manifest uses placeholders
- ✓ Icon resources are customizable
- ✓ No shared external storage

**Multi-Tenant Support**
- ✓ Database encryption ensures data isolation
- ✓ Settings use DataStore (app-private)
- ✓ All data stored in app-private directories

**Deployment Flexibility**
- ✓ Supports Android API 26-34
- ✓ Direct boot aware components
- ✓ Proper permission declarations

### 2. Security Features Tests (SecurityFeaturesE2ETest)

**Nuke Manager**
- ✓ Defaults to disabled
- ✓ Executes database wipe
- ✓ Performs secure multi-pass wipe
- ✓ Wipes all data types (database, settings, cache)
- ✓ Supports multiple trigger sources

**Duress PIN**
- ✓ Not set by default
- ✓ Can be set and verified
- ✓ Cannot be same as normal PIN
- ✓ Returns correct authentication result
- ✓ Can be removed
- ✓ Requires valid length (4-8 digits)

**Nuke Settings**
- ✓ Correct defaults (disabled, secure wipe enabled)
- ✓ USB trigger configurable
- ✓ Failed auth threshold configurable
- ✓ Dead man switch configurable
- ✓ Danger zones can be managed
- ✓ Master enable switch works
- ✓ Wipe options are configurable

**Secure Memory**
- ✓ Clears byte arrays
- ✓ Clears char arrays
- ✓ Handles null arrays
- ✓ Handles empty arrays

**Secure Key Manager**
- ✓ Generates encryption keys
- ✓ Keys are persistent
- ✓ Keys can be deleted

**Integration**
- ✓ Full nuke with duress PIN works
- ✓ Nuke is armed when configured

### 3. Privacy Features Tests (PrivacyFeaturesE2ETest)

**Ephemeral Mode**
- ✓ Defaults to disabled
- ✓ Can be enabled
- ✓ Stores detections in RAM only
- ✓ Detections cleared on clearAll
- ✓ Detections lost on service restart

**Data Retention**
- ✓ Multiple retention periods available
- ✓ Defaults to 3 days
- ✓ Retention period can be changed
- ✓ Converts from hours correctly
- ✓ Deletes old detections

**Location Storage**
- ✓ Default depends on build type
- ✓ Can be disabled
- ✓ Creates detections without location when disabled
- ✓ Stores location when enabled
- ✓ Detections with location can be queried

**Auto-Purge on Screen Lock**
- ✓ Defaults to disabled
- ✓ Can be enabled
- ✓ Deletes all detections when triggered

**Quick Wipe**
- ✓ Confirmation required by default
- ✓ Confirmation can be disabled
- ✓ Deletes all detections

**Ultrasonic Detection Consent**
- ✓ Disabled by default
- ✓ Requires explicit acknowledgment
- ✓ Consent can be revoked
- ✓ Can disable without revoking consent

**Integration**
- ✓ Privacy mode enables all features
- ✓ Ephemeral mode isolates repositories
- ✓ Data retention works with queries

### 4. Detection System Tests (DetectionSystemE2ETest)

**Protocol Coverage**
- ✓ All protocols supported (WiFi, BLE, Cellular, Audio, RF, Satellite)
- ✓ Valid display names and icons

**WiFi Detection**
- ✓ Flock Safety cameras detected
- ✓ SSID patterns match correctly
- ✓ Drones detected

**Cellular Detection**
- ✓ StingRay/IMSI catchers detected
- ✓ All cellular methods available
- ✓ Encryption downgrade detection

**Ultrasonic Detection**
- ✓ Tracking beacons detected
- ✓ All ultrasonic methods available
- ✓ No location for audio detections

**Satellite Detection**
- ✓ NTN connections detected
- ✓ All satellite methods available
- ✓ Unexpected connections flagged

**Device Types**
- ✓ All surveillance categories covered
- ✓ Display names and emojis defined

**Threat Levels**
- ✓ Ordered by severity
- ✓ Score conversion works
- ✓ Can query by level

**Signal Strength**
- ✓ RSSI conversion works
- ✓ Display names defined

**Deduplication**
- ✓ Same MAC reuses detection
- ✓ Same SSID reuses detection
- ✓ Seen count increments

**Queries**
- ✓ Get by device type
- ✓ Get recent detections
- ✓ Get active detections
- ✓ High threat count

**Integration**
- ✓ All protocols can be stored
- ✓ Mark inactive works

### 5. Data Management Tests (DataManagementE2ETest)

**Database Encryption**
- ✓ Uses SQLCipher encryption
- ✓ Database is not plain text
- ✓ Can store and retrieve encrypted data

**Repository Operations**
- ✓ Insert works
- ✓ Update works
- ✓ Delete works
- ✓ Delete all works
- ✓ Bulk insert works
- ✓ Upsert creates or updates

**Export Functionality**
- ✓ CSV export creates valid file
- ✓ JSON export creates valid file
- ✓ KML export creates valid file
- ✓ Handles empty database
- ✓ Handles large datasets efficiently

**Data Integrity**
- ✓ All fields are stored
- ✓ Timestamps are preserved
- ✓ Null fields are handled

**Performance**
- ✓ Insert many detections quickly (<2s for 100)
- ✓ Query is efficient (<1s for 10 queries on 500 records)

**Database Operations**
- ✓ Can be closed and reopened
- ✓ File path is correct

## Test Data Factory

The `TestDataFactory` provides consistent test data:

```kotlin
// Create specific detections
TestDataFactory.createFlockSafetyCameraDetection()
TestDataFactory.createStingrayDetection()
TestDataFactory.createDroneDetection()
TestDataFactory.createUltrasonicBeaconDetection()
TestDataFactory.createSatelliteDetection()

// Create multiple detections
TestDataFactory.createMultipleDetections(count = 100)
TestDataFactory.createMixedProtocolDetections()

// Create settings
TestDataFactory.createDefaultPrivacySettings()
TestDataFactory.createFullyArmedNukeSettings()
TestDataFactory.createSecuritySettings()
```

## Test Helpers

Common utilities in `TestHelpers`:

```kotlin
// Get context
TestHelpers.getContext()

// Wait for condition
TestHelpers.waitForCondition(timeoutMs = 5000) {
    // condition
}

// Clear app data
TestHelpers.clearAppData(context)

// Check encryption
TestHelpers.isDatabaseEncrypted(dbFile)

// Geographic calculations
TestHelpers.calculateDistance(lat1, lon1, lat2, lon2)

// Test data generation
TestHelpers.generateRandomMacAddress()
TestHelpers.generateRandomSsid()
```

## Dependencies

The test suite uses:

- **AndroidX Test** - Core instrumentation testing
- **JUnit 4** - Test framework
- **Hilt** - Dependency injection for tests
- **Kotlinx Coroutines Test** - Coroutine testing utilities
- **MockK** (optional) - Mocking framework

## Best Practices

1. **Isolation**: Each test starts with clean state (`TestHelpers.clearAppData`)
2. **Cleanup**: Tests clean up after themselves in `@After` methods
3. **Deterministic**: Tests don't rely on timing or external state
4. **Documented**: Each test has clear name indicating what it validates
5. **Fast**: Performance tests ensure operations complete quickly
6. **Comprehensive**: Edge cases and error conditions are tested

## OEM Partner Testing Checklist

When validating for OEM deployment, ensure:

- [ ] All build flavors compile and install correctly
- [ ] Branding can be customized via resources
- [ ] App name changes correctly per flavor
- [ ] No hardcoded brand references
- [ ] Database encryption works
- [ ] Privacy defaults match OEM requirements
- [ ] All detection protocols function
- [ ] Export functionality works
- [ ] Security features (nuke, duress) function correctly
- [ ] No analytics or telemetry endpoints hardcoded

## Continuous Integration

These tests should be run:

- On every pull request
- Before releases
- For each build flavor
- On multiple Android versions (API 26-34)

Example CI configuration:

```yaml
test:
  script:
    - ./gradlew connectedSideloadDebugAndroidTest
    - ./gradlew connectedSystemDebugAndroidTest
    - ./gradlew connectedOemDebugAndroidTest
  artifacts:
    reports:
      junit: app/build/test-results/connected*/TEST-*.xml
```

## Contributing

When adding new features:

1. Write tests first (TDD approach)
2. Follow existing test patterns
3. Add test data to `TestDataFactory` if needed
4. Document what your tests validate
5. Ensure tests are deterministic
6. Keep tests fast (<2s each)

## Troubleshooting

**Tests fail with "Database locked"**
- Ensure proper cleanup in `@After`
- Close database connections explicitly

**Tests timeout**
- Check for infinite loops in wait conditions
- Increase timeout for slow operations

**Tests fail on CI but pass locally**
- Ensure tests don't depend on specific device state
- Check for timing issues

**Hilt injection fails**
- Ensure `@HiltAndroidTest` annotation is present
- Call `hiltRule.inject()` in `@Before`

## Contact

For questions about the test suite or OEM deployment, contact the development team.

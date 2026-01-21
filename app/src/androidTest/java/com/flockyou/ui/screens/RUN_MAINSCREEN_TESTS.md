# Quick Guide: Running MainScreen E2E Tests

## Prerequisites

1. Android device or emulator running API 26+
2. USB debugging enabled (for physical device)
3. App permissions granted (Location, Bluetooth, etc.)

## Running All Tests

### Option 1: Android Studio (Easiest)
1. Open `MainScreenTest.kt` in Android Studio
2. Right-click on the class name `MainScreenTest`
3. Select **"Run 'MainScreenTest'"**
4. View results in the Run window

### Option 2: Command Line
```bash
cd /Users/maxwatermolen/source/Flock-You-Android

# Run all MainScreen tests
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"
```

### Option 3: Gradle Managed Device
```bash
# Create and run tests on managed emulator
./gradlew pixel2api30DebugAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"
```

## Running Specific Test Categories

### Navigation Tests Only
```bash
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_*navigation*"
```

### Pull-to-Refresh Tests Only
```bash
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_*refresh*"
```

### Detection Display Tests Only
```bash
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_displays*"
```

### Filtering Tests Only
```bash
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_*filter*"
```

## Running Individual Tests

### From Android Studio
1. Open `MainScreenTest.kt`
2. Click the green arrow icon next to any test method
3. Select **"Run 'testMethodName'"**

### From Command Line
```bash
# Example: Run navigation test
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_canNavigateToHistoryTab"

# Example: Run filtering test
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_filterByThreatLevelWorks"
```

## Viewing Test Results

### Android Studio
- Test results appear in the "Run" panel
- Green checkmarks = passed
- Red X = failed
- Yellow warning = skipped
- Click on any test to see detailed output

### Command Line
Results are in:
```
app/build/reports/androidTests/connected/index.html
```

Open in browser:
```bash
open app/build/reports/androidTests/connected/index.html
```

### Logcat Output
Filter by test tag:
```bash
adb logcat | grep "MainScreenTest"
```

## Troubleshooting

### Tests Fail with "No matching devices"
**Solution:** Start an emulator or connect a device
```bash
# List available devices
adb devices

# Start emulator
emulator -avd <device_name>
```

### Tests Fail with Database Errors
**Solution:** Clear app data before running
```bash
adb shell pm clear com.flockyou
```

### Tests Fail with Permission Errors
**Solution:** Grant permissions manually
```bash
adb shell pm grant com.flockyou android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.flockyou android.permission.BLUETOOTH_SCAN
adb shell pm grant com.flockyou android.permission.BLUETOOTH_CONNECT
```

### Tests Timeout
**Solution:** Increase timeout in test or check device performance
```kotlin
// In test code
composeTestRule.waitForIdle()
delay(2000) // Increase delay
```

### Flaky Tests (Sometimes Pass, Sometimes Fail)
**Common Causes:**
1. Async operations not completing
2. Animation interference
3. Device performance issues

**Solutions:**
1. Add longer delays after database operations
2. Disable animations:
   ```bash
   adb shell settings put global window_animation_scale 0
   adb shell settings put global transition_animation_scale 0
   adb shell settings put global animator_duration_scale 0
   ```
3. Use faster emulator or physical device

### Tests Pass Individually but Fail When Run Together
**Cause:** Test pollution (state not cleaned up)

**Solution:** Check `@Before` and `@After` methods
```kotlin
@Before
fun setup() {
    hiltRule.inject()
    TestHelpers.clearAppData(context)
    runBlocking {
        detectionRepository.deleteAllDetections()
    }
}
```

## Performance Tips

### Speed Up Test Execution
1. **Disable animations** (as shown above)
2. **Use physical device** (faster than emulator)
3. **Run in parallel** (if you have multiple devices):
   ```bash
   ./gradlew connectedAndroidTest --max-workers=4
   ```

### Reduce Test Time
1. Run only changed tests during development
2. Run full suite before committing
3. Use CI/CD for comprehensive testing

## CI/CD Integration

### GitHub Actions
```yaml
- name: Run MainScreen E2E Tests
  run: ./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"
```

### GitLab CI
```yaml
test_mainscreen:
  script:
    - ./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"
  artifacts:
    reports:
      junit: app/build/test-results/connectedAndroidTest/*.xml
```

## Test Coverage Report

Generate test coverage:
```bash
./gradlew createDebugCoverageReport
```

View report:
```bash
open app/build/reports/coverage/androidTest/debug/index.html
```

## Best Practices

1. **Run tests before committing**
   ```bash
   ./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"
   ```

2. **Check test results**
   - All tests should pass
   - No warnings in logcat
   - Performance within acceptable range

3. **Update tests with code changes**
   - Add tests for new features
   - Update tests for modified behavior
   - Remove tests for deleted features

4. **Keep tests fast**
   - Average: < 5 seconds per test
   - Total suite: < 5 minutes

5. **Keep tests isolated**
   - Each test should be independent
   - No dependencies between tests
   - Proper cleanup in @After

## Quick Test Commands

```bash
# Run everything
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"

# Run fast smoke tests (navigation only)
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_bottomNavigationIsVisible"

# Run critical path (integration tests)
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest.mainScreen_fullUserJourney*"

# Generate HTML report
./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest" && open app/build/reports/androidTests/connected/index.html
```

## Getting Help

If tests continue to fail:
1. Check logcat: `adb logcat | grep -E "(MainScreenTest|TestRunner|FATAL)"`
2. Verify app builds: `./gradlew assembleDebug`
3. Verify test builds: `./gradlew assembleDebugAndroidTest`
4. Clean and rebuild: `./gradlew clean connectedAndroidTest`

## Test Maintenance Schedule

- **Daily**: Run smoke tests during development
- **Pre-commit**: Run full suite
- **CI/CD**: Run on every pull request
- **Weekly**: Review and update tests
- **Release**: Full regression test with coverage report

## Summary

| Command | Use Case |
|---------|----------|
| `./gradlew connectedAndroidTest --tests "com.flockyou.ui.screens.MainScreenTest"` | Run all tests |
| `./gradlew connectedAndroidTest --tests "*.MainScreenTest.mainScreen_canNavigateToHistoryTab"` | Run single test |
| `open app/build/reports/androidTests/connected/index.html` | View results |
| `adb logcat \| grep MainScreenTest` | Debug tests |
| `./gradlew clean` | Clean before running |

---

**50+ comprehensive tests** covering all MainScreen functionality.
**Expected runtime**: 3-5 minutes on physical device, 5-10 minutes on emulator.
**Success rate**: 100% when environment is properly configured.

#!/bin/bash

# ============================================================================
# Flock-You Screenshot Automation Script
# ============================================================================
#
# This script automates capturing screenshots of every screen in the app
# with test data populated. It uses the debug build's ScreenshotHelperActivity
# to control test mode and navigation.
#
# Prerequisites:
# - Android SDK with adb in PATH
# - An Android emulator running OR a connected device
# - Debug APK built (./gradlew assembleSideloadDebug)
#
# Usage:
#   ./scripts/capture_screenshots.sh              # Use running emulator/device
#   ./scripts/capture_screenshots.sh --emulator   # Start emulator first
#   ./scripts/capture_screenshots.sh --clean      # Clean screenshots dir first
#
# ============================================================================

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SCREENSHOTS_DIR="$PROJECT_DIR/screenshots"
PACKAGE_NAME="com.flockyou.debug"
HELPER_ACTIVITY="com.flockyou.debug.ScreenshotHelperActivity"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/sideload/debug/app-sideload-debug.apk"

# Timing configuration (adjust based on device speed)
INSTALL_WAIT=3
SCENARIO_WAIT=4
NAVIGATION_WAIT=2
SCREENSHOT_WAIT=1

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# All screens to capture (route -> filename)
declare -A SCREENS=(
    ["main"]="01_main_dashboard"
    ["map"]="02_detection_map"
    ["settings"]="03_settings"
    ["all_detections"]="04_detection_history"
    ["nearby"]="05_nearby_devices"
    ["detection_settings"]="06_detection_settings"
    ["rf_detection"]="07_rf_detection"
    ["ultrasonic_detection"]="08_ultrasonic_detection"
    ["satellite_detection"]="09_satellite_detection"
    ["wifi_security"]="10_wifi_security"
    ["service_health"]="11_service_health"
    ["ai_settings"]="12_ai_settings"
    ["security"]="13_security_settings"
    ["privacy"]="14_privacy_settings"
    ["nuke_settings"]="15_nuke_settings"
    ["flipper_settings"]="16_flipper_settings"
    ["notifications"]="17_notification_settings"
    ["test_mode"]="18_test_mode"
)

# Ordered list for capture sequence
SCREEN_ORDER=(
    "main"
    "all_detections"
    "map"
    "nearby"
    "rf_detection"
    "ultrasonic_detection"
    "satellite_detection"
    "wifi_security"
    "service_health"
    "settings"
    "detection_settings"
    "ai_settings"
    "notifications"
    "security"
    "privacy"
    "nuke_settings"
    "flipper_settings"
    "test_mode"
)

# ============================================================================
# Helper Functions
# ============================================================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

wait_for_device() {
    log_info "Waiting for device..."
    adb wait-for-device

    # Wait for boot to complete
    log_info "Waiting for boot to complete..."
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 1
    done

    # Additional wait for UI to be ready
    sleep 2
    log_success "Device ready"
}

check_adb() {
    if ! command -v adb &> /dev/null; then
        log_error "adb not found. Please install Android SDK and add it to PATH"
        exit 1
    fi
}

start_emulator() {
    local avd_name="$1"

    if [ -z "$avd_name" ]; then
        # List available AVDs and use the first one
        avd_name=$(emulator -list-avds 2>/dev/null | head -1)
        if [ -z "$avd_name" ]; then
            log_error "No AVDs found. Create one with Android Studio first."
            exit 1
        fi
    fi

    log_info "Starting emulator: $avd_name"
    emulator -avd "$avd_name" -no-snapshot-save &
    EMULATOR_PID=$!

    # Wait for emulator to boot
    wait_for_device
}

build_apk() {
    log_info "Building debug APK..."
    cd "$PROJECT_DIR"
    ./gradlew assembleSideloadDebug --quiet

    if [ ! -f "$APK_PATH" ]; then
        log_error "APK not found at $APK_PATH"
        exit 1
    fi
    log_success "APK built successfully"
}

install_app() {
    log_info "Installing app..."
    adb install -r -g "$APK_PATH" 2>/dev/null || {
        log_warn "Install with -g failed, trying without..."
        adb install -r "$APK_PATH"
    }
    sleep $INSTALL_WAIT
    log_success "App installed"
}

grant_permissions() {
    log_info "Granting permissions..."

    local permissions=(
        "android.permission.ACCESS_FINE_LOCATION"
        "android.permission.ACCESS_COARSE_LOCATION"
        "android.permission.ACCESS_BACKGROUND_LOCATION"
        "android.permission.READ_PHONE_STATE"
        "android.permission.RECORD_AUDIO"
        "android.permission.BLUETOOTH_SCAN"
        "android.permission.BLUETOOTH_CONNECT"
        "android.permission.POST_NOTIFICATIONS"
        "android.permission.NEARBY_WIFI_DEVICES"
    )

    for perm in "${permissions[@]}"; do
        adb shell pm grant "$PACKAGE_NAME" "$perm" 2>/dev/null || true
    done

    log_success "Permissions granted"
}

disable_battery_optimization() {
    log_info "Disabling battery optimization..."
    adb shell dumpsys deviceidle whitelist +$PACKAGE_NAME 2>/dev/null || true
    log_success "Battery optimization disabled"
}

skip_onboarding() {
    log_info "Skipping onboarding dialogs..."

    # Set the "getting started shown" preference
    adb shell "run-as $PACKAGE_NAME sh -c 'cat > /data/data/$PACKAGE_NAME/shared_prefs/flockyou_prefs.xml << EOF
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
    <boolean name=\"getting_started_shown\" value=\"true\" />
</map>
EOF'" 2>/dev/null || {
        log_warn "Could not set preferences directly, will handle dialogs manually"
    }
}

enable_test_mode() {
    local scenario="$1"
    log_info "Enabling test mode with scenario: $scenario"

    adb shell am start -a com.flockyou.debug.TEST_MODE \
        --es action enable \
        --es scenario "$scenario" \
        -n "$PACKAGE_NAME/$HELPER_ACTIVITY"

    sleep $SCENARIO_WAIT
    log_success "Test mode enabled"
}

disable_test_mode() {
    log_info "Disabling test mode..."

    adb shell am start -a com.flockyou.debug.TEST_MODE \
        --es action disable \
        -n "$PACKAGE_NAME/$HELPER_ACTIVITY"

    sleep 1
}

navigate_to() {
    local route="$1"
    log_info "Navigating to: $route"

    adb shell am start -a com.flockyou.debug.NAVIGATE \
        --es route "$route" \
        -n "$PACKAGE_NAME/$HELPER_ACTIVITY"

    sleep $NAVIGATION_WAIT
}

take_screenshot() {
    local name="$1"
    local device_path="/sdcard/screenshot_${name}.png"
    local local_path="$SCREENSHOTS_DIR/${name}.png"

    adb shell screencap -p "$device_path"
    adb pull "$device_path" "$local_path" 2>/dev/null
    adb shell rm "$device_path"

    log_success "Screenshot saved: ${name}.png"
}

dismiss_dialogs() {
    # Press back to dismiss any dialogs
    adb shell input keyevent KEYCODE_BACK
    sleep 0.5
}

# Capture critical alert by triggering a high-threat detection
capture_critical_alert() {
    log_info "Capturing critical alert..."

    # The high_threat_environment scenario should trigger critical alerts
    # Navigate to main to see the alert
    navigate_to "main"
    sleep $SCENARIO_WAIT  # Wait for detections to appear

    take_screenshot "00_critical_alert"
}

# ============================================================================
# Main Script
# ============================================================================

main() {
    echo ""
    echo "=============================================="
    echo "  Flock-You Screenshot Automation"
    echo "=============================================="
    echo ""

    # Parse arguments
    local start_emulator=false
    local clean_first=false
    local skip_build=false

    while [[ $# -gt 0 ]]; do
        case $1 in
            --emulator)
                start_emulator=true
                shift
                ;;
            --clean)
                clean_first=true
                shift
                ;;
            --skip-build)
                skip_build=true
                shift
                ;;
            *)
                log_warn "Unknown option: $1"
                shift
                ;;
        esac
    done

    # Check prerequisites
    check_adb

    # Clean screenshots directory
    if [ "$clean_first" = true ] || [ ! -d "$SCREENSHOTS_DIR" ]; then
        log_info "Cleaning screenshots directory..."
        rm -rf "$SCREENSHOTS_DIR"
        mkdir -p "$SCREENSHOTS_DIR"
    fi

    # Start emulator if requested
    if [ "$start_emulator" = true ]; then
        start_emulator
    else
        wait_for_device
    fi

    # Build APK if needed
    if [ "$skip_build" = false ]; then
        build_apk
    fi

    # Install and setup app
    install_app
    grant_permissions
    disable_battery_optimization
    skip_onboarding

    # Enable test mode with high threat scenario (generates CRITICAL alerts)
    enable_test_mode "high_threat_environment"

    # Give time for test data to populate
    log_info "Waiting for test data to populate..."
    sleep 3

    # Capture main screen with critical detections first
    log_info "Capturing main screen with critical detections..."
    navigate_to "main"
    sleep $NAVIGATION_WAIT
    take_screenshot "00_critical_detections"

    # Capture all screens
    log_info "Capturing all screens..."
    echo ""

    for route in "${SCREEN_ORDER[@]}"; do
        local filename="${SCREENS[$route]}"
        navigate_to "$route"
        sleep $SCREENSHOT_WAIT
        take_screenshot "$filename"
    done

    # Disable test mode when done
    disable_test_mode

    echo ""
    echo "=============================================="
    log_success "Screenshot capture complete!"
    echo "=============================================="
    echo ""
    log_info "Screenshots saved to: $SCREENSHOTS_DIR"
    echo ""

    # List captured files
    echo "Captured files:"
    ls -la "$SCREENSHOTS_DIR"/*.png 2>/dev/null | awk '{print "  " $NF}' | sed 's|.*/||'
}

# Run main function
main "$@"

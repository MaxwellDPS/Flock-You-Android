#!/bin/bash
#
# Flock You - GrapheneOS Integration Helper Script
#
# This script automates the integration of Flock You into a GrapheneOS
# (or other AOSP-based ROM) build tree.
#
# Usage:
#   ./integrate-grapheneos.sh <grapheneos-source-path> [signing-mode]
#
# Arguments:
#   grapheneos-source-path  Path to GrapheneOS/AOSP source tree
#   signing-mode            "platform" (default) or "presigned"
#
# Examples:
#   ./integrate-grapheneos.sh ~/grapheneos
#   ./integrate-grapheneos.sh ~/grapheneos presigned
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check arguments
if [ -z "$1" ]; then
    echo "Usage: $0 <grapheneos-source-path> [signing-mode]"
    echo ""
    echo "Arguments:"
    echo "  grapheneos-source-path  Path to GrapheneOS/AOSP source tree"
    echo "  signing-mode            'platform' (default) or 'presigned'"
    echo ""
    echo "Examples:"
    echo "  $0 ~/grapheneos"
    echo "  $0 ~/grapheneos presigned"
    exit 1
fi

AOSP_ROOT="$1"
SIGNING_MODE="${2:-platform}"
TARGET_DIR="$AOSP_ROOT/vendor/flockyou"

# Validate AOSP source tree
if [ ! -f "$AOSP_ROOT/build/envsetup.sh" ]; then
    print_error "Invalid AOSP/GrapheneOS source path: $AOSP_ROOT"
    print_error "Expected to find build/envsetup.sh"
    exit 1
fi

# Validate signing mode
if [ "$SIGNING_MODE" != "platform" ] && [ "$SIGNING_MODE" != "presigned" ]; then
    print_error "Invalid signing mode: $SIGNING_MODE"
    print_error "Valid options: 'platform' or 'presigned'"
    exit 1
fi

print_info "Flock You - GrapheneOS Integration"
print_info "==================================="
print_info "Source path: $AOSP_ROOT"
print_info "Target path: $TARGET_DIR"
print_info "Signing mode: $SIGNING_MODE"
echo ""

# Check if APK exists, if not, build it
APK_PATH=""
if [ "$SIGNING_MODE" = "platform" ]; then
    APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/oem/release/app-oem-release.apk"
    BUILD_CMD="assembleOemRelease"
else
    APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/system/release/app-system-release.apk"
    BUILD_CMD="assembleSystemRelease"
fi

if [ ! -f "$APK_PATH" ]; then
    print_warn "APK not found at: $APK_PATH"
    print_info "Building APK with: ./gradlew $BUILD_CMD"

    cd "$PROJECT_ROOT"
    if [ -f "./gradlew" ]; then
        ./gradlew "$BUILD_CMD"
    else
        print_error "Gradle wrapper not found. Please build the APK manually:"
        print_error "  cd $PROJECT_ROOT && ./gradlew $BUILD_CMD"
        exit 1
    fi

    if [ ! -f "$APK_PATH" ]; then
        print_error "Build failed or APK not found after build"
        exit 1
    fi
fi

print_info "Using APK: $APK_PATH"
echo ""

# Create target directory
print_info "Creating target directory..."
mkdir -p "$TARGET_DIR"

# Copy files
print_info "Copying integration files..."
cp "$SCRIPT_DIR/Android.bp" "$TARGET_DIR/"
cp "$SCRIPT_DIR/Android.mk" "$TARGET_DIR/"
cp "$SCRIPT_DIR/flockyou.mk" "$TARGET_DIR/"
cp "$SCRIPT_DIR/privapp-permissions-flockyou.xml" "$TARGET_DIR/"
cp "$APK_PATH" "$TARGET_DIR/FlockYou.apk"

# Modify Android.bp for signing mode if presigned
if [ "$SIGNING_MODE" = "presigned" ]; then
    print_info "Configuring for presigned APK..."
    sed -i.bak 's/certificate: "platform"/certificate: "PRESIGNED"/' "$TARGET_DIR/Android.bp"
    sed -i.bak 's/LOCAL_CERTIFICATE := platform/LOCAL_CERTIFICATE := PRESIGNED/' "$TARGET_DIR/Android.mk"
    rm -f "$TARGET_DIR/Android.bp.bak" "$TARGET_DIR/Android.mk.bak"
fi

print_info "Files copied successfully!"
echo ""

# Display next steps
print_info "Integration complete!"
echo ""
echo "Next steps:"
echo ""
echo "1. Add FlockYou to your device configuration. Edit your device.mk:"
echo "   ${YELLOW}PRODUCT_PACKAGES += FlockYou${NC}"
echo ""
echo "   Or include the makefile:"
echo "   ${YELLOW}\$(call inherit-product, vendor/flockyou/flockyou.mk)${NC}"
echo ""
echo "2. Build your ROM as usual:"
echo "   ${YELLOW}source build/envsetup.sh${NC}"
echo "   ${YELLOW}lunch <target>${NC}"
echo "   ${YELLOW}m${NC}"
echo ""
echo "The app will be installed to /system_ext/priv-app/FlockYou/"
echo "with privileged permissions pre-granted."
echo ""

if [ "$SIGNING_MODE" = "platform" ]; then
    print_info "Using platform signing - app will have OEM privileges"
    print_info "(IMEI/IMSI access, full modem access, etc.)"
else
    print_info "Using presigned APK - app will have System privileges"
    print_info "(No IMEI/IMSI access, limited modem access)"
fi

echo ""
print_info "Done!"

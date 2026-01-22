#!/bin/bash
set -euo pipefail

DEVICE="${DEVICE:-husky}"
BUILD_TYPE="${BUILD_TYPE:-user}"

echo "=== Building GrapheneOS ==="
echo "Device: ${DEVICE}"
echo "Build type: ${BUILD_TYPE}"
echo "Parallel jobs: $(nproc)"

cd /src

# Setup build environment
source build/envsetup.sh

# Select device - GrapheneOS uses aosp_DEVICE-TYPE format
# Map device codenames to their lunch targets
case "${DEVICE}" in
    husky|shiba)
        LUNCH_TARGET="aosp_${DEVICE}-${BUILD_TYPE}"
        ;;
    felix|tangorpro|lynx|cheetah|panther|bluejay|oriole|raven)
        LUNCH_TARGET="aosp_${DEVICE}-${BUILD_TYPE}"
        ;;
    *)
        # Default format
        LUNCH_TARGET="aosp_${DEVICE}-${BUILD_TYPE}"
        ;;
esac

echo "Lunch target: ${LUNCH_TARGET}"
lunch "${LUNCH_TARGET}"

# Apply OEM customizations
if [ -f "/scripts/oem-setup.sh" ]; then
    echo "Applying OEM customizations..."
    /scripts/oem-setup.sh
fi

# Build
echo "Starting build..."
time m -j$(nproc)

echo "=== Build complete ==="
ls -la out/target/product/${DEVICE}/*.img 2>/dev/null || true
ls -la out/target/product/${DEVICE}/*.zip 2>/dev/null || true

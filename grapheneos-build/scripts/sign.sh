#!/bin/bash
set -euo pipefail

DEVICE="${DEVICE:-husky}"
KEYS_DIR="${KEYS_DIR:-/keys}"
USE_YUBIKEY="${USE_YUBIKEY:-false}"

echo "=== Signing GrapheneOS build ==="
echo "Device: ${DEVICE}"
echo "Keys directory: ${KEYS_DIR}"
echo "YubiKey signing: ${USE_YUBIKEY}"

cd /src

# Check for keys
if [ "${USE_YUBIKEY}" = "true" ]; then
    # Check for YubiKey signing config
    if [ ! -f "${KEYS_DIR}/${DEVICE}/yubikey-signing.conf" ]; then
        echo "ERROR: YubiKey config not found at ${KEYS_DIR}/${DEVICE}/yubikey-signing.conf"
        echo "Generate keys first with: /scripts/yubikey-setup.sh generate"
        exit 1
    fi

    # Verify YubiKey is present
    if ! ykman info &>/dev/null; then
        echo "ERROR: No YubiKey detected. Insert YubiKey and try again."
        exit 1
    fi

    echo "YubiKey detected - using hardware signing"
    source "${KEYS_DIR}/${DEVICE}/yubikey-signing.conf"

    # Ensure pcscd is running
    service pcscd start 2>/dev/null || true
else
    # File-based keys
    if [ ! -d "${KEYS_DIR}/${DEVICE}" ]; then
        echo "ERROR: Keys not found at ${KEYS_DIR}/${DEVICE}"
        echo "Generate keys first with: /scripts/generate-keys.sh"
        echo "Or use YubiKey: USE_YUBIKEY=true /scripts/sign.sh"
        exit 1
    fi
fi

# Source build environment
source build/envsetup.sh
lunch ${DEVICE}-user

# Sign target files
echo "Signing target files..."

if [ "${USE_YUBIKEY}" = "true" ]; then
    # Use YubiKey signing wrapper
    if [ -f "${KEYS_DIR}/${DEVICE}/sign_target_files_apks_yubikey.sh" ]; then
        echo "Using YubiKey signing wrapper..."
        # Create output directory
        mkdir -p "out/release-${DEVICE}"
        "${KEYS_DIR}/${DEVICE}/sign_target_files_apks_yubikey.sh" \
            "out/target/product/${DEVICE}/${DEVICE}-target_files.zip" \
            "out/release-${DEVICE}/signed-target_files.zip"
    else
        echo "ERROR: YubiKey signing wrapper not found"
        echo "Run: /scripts/yubikey-setup.sh wrapper"
        exit 1
    fi
else
    # Standard file-based signing - GrapheneOS release script
    # Check for the script location (may vary by version)
    if [ -f "script/release.sh" ]; then
        script/release.sh "${DEVICE}"
    elif [ -f "vendor/grapheneos/script/release.sh" ]; then
        vendor/grapheneos/script/release.sh "${DEVICE}"
    else
        echo "ERROR: release.sh not found"
        echo "Trying manual signing with sign_target_files_apks..."
        mkdir -p "out/release-${DEVICE}"
        python3 build/tools/releasetools/sign_target_files_apks.py \
            -o \
            -d "${KEYS_DIR}/${DEVICE}" \
            "out/target/product/${DEVICE}/${DEVICE}-target_files.zip" \
            "out/release-${DEVICE}/signed-target_files.zip"
    fi
fi

echo "=== Signing complete ==="
ls -la out/release-${DEVICE}*/ 2>/dev/null || true

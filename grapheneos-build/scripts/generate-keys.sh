#!/bin/bash
set -euo pipefail

DEVICE="${DEVICE:-husky}"
KEYS_DIR="${KEYS_DIR:-/keys}"
KEY_SIZE="${KEY_SIZE:-4096}"

echo "=== Generating GrapheneOS Signing Keys ==="
echo "Device: ${DEVICE}"
echo "Output: ${KEYS_DIR}/${DEVICE}"
echo "Key size: ${KEY_SIZE}"

mkdir -p "${KEYS_DIR}/${DEVICE}"
cd "${KEYS_DIR}/${DEVICE}"

# Key names required for GrapheneOS
KEYS=(
    "releasekey"
    "platform"
    "shared"
    "media"
    "networkstack"
    "sdk_sandbox"
    "bluetooth"
)

# AVB key for verified boot
AVB_KEY="avb"

echo "Generating APK signing keys..."
for key in "${KEYS[@]}"; do
    if [ ! -f "${key}.pk8" ]; then
        echo "  Generating ${key}..."
        # make_key expects password on stdin - use empty password for automation
        # The script prompts twice (password + confirm), so provide two empty lines
        printf '\n\n' | /src/development/tools/make_key "${key}" \
            "/CN=GrapheneOS ${key}/"
    else
        echo "  ${key} already exists, skipping"
    fi
done

echo "Generating AVB key..."
if [ ! -f "${AVB_KEY}.pem" ]; then
    openssl genrsa -out "${AVB_KEY}.pem" ${KEY_SIZE}
    /src/external/avb/avbtool.py extract_public_key \
        --key "${AVB_KEY}.pem" \
        --output "${AVB_KEY}_pubkey.bin"
else
    echo "  AVB key already exists, skipping"
fi

echo ""
echo "=== Keys Generated ==="
echo "IMPORTANT: Back up these keys securely!"
echo "Lost keys = cannot update your devices"
ls -la "${KEYS_DIR}/${DEVICE}/"

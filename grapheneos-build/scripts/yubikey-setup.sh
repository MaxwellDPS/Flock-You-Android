#!/bin/bash
set -euo pipefail

# YubiKey + step-ca/step-kms signing setup for GrapheneOS
# Requires: YubiKey 5 with PIV support, step-cli, step-kms-plugin

DEVICE="${DEVICE:-husky}"
KEYS_DIR="${KEYS_DIR:-/keys}"
YUBIKEY_PIN="${YUBIKEY_PIN:-}"
YUBIKEY_MGMT_KEY="${YUBIKEY_MGMT_KEY:-010203040506070801020304050607080102030405060708}"
STEP_CA_URL="${STEP_CA_URL:-}"
STEP_CA_FINGERPRINT="${STEP_CA_FINGERPRINT:-}"

# PIV slot assignments for GrapheneOS keys
# 9a - Authentication (releasekey)
# 9c - Digital Signature (platform)
# 9d - Key Management (shared)
# 9e - Card Authentication (media)
# 82-95 - Retired slots for additional keys

declare -A KEY_SLOTS=(
    ["releasekey"]="9a"
    ["platform"]="9c"
    ["shared"]="9d"
    ["media"]="9e"
    ["networkstack"]="82"
    ["sdk_sandbox"]="83"
    ["bluetooth"]="84"
    ["avb"]="85"
)

echo "=== YubiKey Signing Setup for GrapheneOS ==="
echo "Device: ${DEVICE}"
echo "Keys directory: ${KEYS_DIR}/${DEVICE}"

# Check for required tools
check_dependencies() {
    echo "Checking dependencies..."

    local missing=()

    command -v step &>/dev/null || missing+=("step-cli")
    command -v ykman &>/dev/null || missing+=("yubikey-manager")
    command -v pkcs11-tool &>/dev/null || missing+=("opensc")

    if [ "${#missing[@]}" -gt 0 ]; then
        echo "ERROR: Missing required tools: ${missing[*]}"
        echo ""
        echo "Install with:"
        echo "  apt-get install -y opensc yubikey-manager"
        echo "  wget https://dl.smallstep.com/gh-release/cli/docs-cli-install/v0.25.0/step-cli_0.25.0_amd64.deb"
        echo "  dpkg -i step-cli_0.25.0_amd64.deb"
        echo "  step plugin install kms"
        exit 1
    fi

    # Check step-kms-plugin
    if ! step kms --help &>/dev/null; then
        echo "Installing step-kms-plugin..."
        step plugin install kms
    fi

    echo "All dependencies satisfied"
}

# Detect YubiKey
detect_yubikey() {
    echo "Detecting YubiKey..."

    if ! ykman info &>/dev/null; then
        echo "ERROR: No YubiKey detected"
        echo "Please insert your YubiKey and try again"
        exit 1
    fi

    echo "YubiKey detected:"
    ykman info | head -5

    # Check PIV capability
    if ! ykman piv info &>/dev/null; then
        echo "ERROR: PIV not available on this YubiKey"
        exit 1
    fi
}

# Get PIN if not provided
get_pin() {
    if [ -z "${YUBIKEY_PIN}" ]; then
        echo -n "Enter YubiKey PIV PIN: "
        read -s YUBIKEY_PIN
        echo ""
    fi
}

# Generate key in YubiKey slot
generate_yubikey_key() {
    local key_name="$1"
    local slot="${KEY_SLOTS[$key_name]}"
    local key_type="${2:-ECCP384}"  # ECCP384 for better security, RSA4096 for compatibility

    echo "Generating ${key_name} in slot ${slot}..."

    # Check if slot already has a key
    if ykman piv keys export "${slot}" /dev/null 2>/dev/null; then
        echo "  WARNING: Slot ${slot} already has a key"
        read -p "  Overwrite? (y/N) " confirm
        if [ "${confirm}" != "y" ]; then
            echo "  Skipping ${key_name}"
            return 0
        fi
    fi

    # Generate key on YubiKey
    ykman piv keys generate \
        --algorithm "${key_type}" \
        --pin-policy ONCE \
        --touch-policy CACHED \
        "${slot}" \
        "${KEYS_DIR}/${DEVICE}/${key_name}_pub.pem" \
        --management-key "${YUBIKEY_MGMT_KEY}"

    # Generate self-signed certificate
    ykman piv certificates generate \
        --subject "CN=GrapheneOS ${key_name}" \
        --valid-days 3650 \
        "${slot}" \
        "${KEYS_DIR}/${DEVICE}/${key_name}_pub.pem" \
        --management-key "${YUBIKEY_MGMT_KEY}" \
        --pin "${YUBIKEY_PIN}"

    echo "  Generated ${key_name} in slot ${slot}"
}

# Generate key with step-ca (if configured)
generate_stepca_key() {
    local key_name="$1"
    local slot="${KEY_SLOTS[$key_name]}"

    if [ -z "${STEP_CA_URL}" ]; then
        echo "step-ca not configured, using self-signed"
        generate_yubikey_key "$key_name"
        return
    fi

    echo "Generating ${key_name} with step-ca..."

    # Generate key on YubiKey
    ykman piv keys generate \
        --algorithm ECCP384 \
        --pin-policy ONCE \
        --touch-policy CACHED \
        "${slot}" \
        "${KEYS_DIR}/${DEVICE}/${key_name}_csr_pub.pem" \
        --management-key "${YUBIKEY_MGMT_KEY}"

    # Create CSR using step-kms
    step certificate create \
        --csr \
        --kms "yubikey:slot-id=${slot}" \
        "GrapheneOS ${key_name}" \
        "${KEYS_DIR}/${DEVICE}/${key_name}.csr" \
        --pin "${YUBIKEY_PIN}"

    # Sign with step-ca
    step ca sign \
        --ca-url "${STEP_CA_URL}" \
        --root "${STEP_CA_FINGERPRINT}" \
        --not-after 8760h \
        "${KEYS_DIR}/${DEVICE}/${key_name}.csr" \
        "${KEYS_DIR}/${DEVICE}/${key_name}.crt"

    # Import certificate to YubiKey
    ykman piv certificates import \
        "${slot}" \
        "${KEYS_DIR}/${DEVICE}/${key_name}.crt" \
        --management-key "${YUBIKEY_MGMT_KEY}"

    echo "  Generated ${key_name} with step-ca certificate"
}

# Export public key in Android signing format
export_android_pubkey() {
    local key_name="$1"
    local slot="${KEY_SLOTS[$key_name]}"

    echo "Exporting ${key_name} public key for Android..."

    # Export certificate
    ykman piv certificates export "${slot}" \
        "${KEYS_DIR}/${DEVICE}/${key_name}.x509.pem"

    # Convert to DER
    openssl x509 -in "${KEYS_DIR}/${DEVICE}/${key_name}.x509.pem" \
        -outform DER \
        -out "${KEYS_DIR}/${DEVICE}/${key_name}.x509.der"

    echo "  Exported ${key_name}.x509.pem and ${key_name}.x509.der"
}

# Create PKCS#11 configuration for Android signing
create_pkcs11_config() {
    echo "Creating PKCS#11 configuration..."

    cat > "${KEYS_DIR}/${DEVICE}/yubikey-pkcs11.conf" << 'EOF'
# YubiKey PKCS#11 configuration for Android signing
name = YubiKey
library = /usr/lib/x86_64-linux-gnu/libykcs11.so
slot = 0
EOF

    cat > "${KEYS_DIR}/${DEVICE}/yubikey-signing.conf" << EOF
# GrapheneOS YubiKey signing configuration
# Source this file before signing

export ANDROID_PK11_LIB="/usr/lib/x86_64-linux-gnu/libykcs11.so"
export ANDROID_PK11_SLOT="0"

# Key slot mappings
export RELEASEKEY_SLOT="${KEY_SLOTS[releasekey]}"
export PLATFORM_SLOT="${KEY_SLOTS[platform]}"
export SHARED_SLOT="${KEY_SLOTS[shared]}"
export MEDIA_SLOT="${KEY_SLOTS[media]}"
export NETWORKSTACK_SLOT="${KEY_SLOTS[networkstack]}"
export SDK_SANDBOX_SLOT="${KEY_SLOTS[sdk_sandbox]}"
export BLUETOOTH_SLOT="${KEY_SLOTS[bluetooth]}"
export AVB_SLOT="${KEY_SLOTS[avb]}"

# step-kms URIs for each key
export RELEASEKEY_KMS="yubikey:slot-id=${KEY_SLOTS[releasekey]}"
export PLATFORM_KMS="yubikey:slot-id=${KEY_SLOTS[platform]}"
export SHARED_KMS="yubikey:slot-id=${KEY_SLOTS[shared]}"
export MEDIA_KMS="yubikey:slot-id=${KEY_SLOTS[media]}"
export NETWORKSTACK_KMS="yubikey:slot-id=${KEY_SLOTS[networkstack]}"
export SDK_SANDBOX_KMS="yubikey:slot-id=${KEY_SLOTS[sdk_sandbox]}"
export BLUETOOTH_KMS="yubikey:slot-id=${KEY_SLOTS[bluetooth]}"
export AVB_KMS="yubikey:slot-id=${KEY_SLOTS[avb]}"
EOF

    echo "Created ${KEYS_DIR}/${DEVICE}/yubikey-signing.conf"
}

# Generate all keys
generate_all_keys() {
    mkdir -p "${KEYS_DIR}/${DEVICE}"

    echo ""
    echo "=== Generating GrapheneOS Signing Keys on YubiKey ==="
    echo ""
    echo "This will generate keys in the following PIV slots:"
    for key in "${!KEY_SLOTS[@]}"; do
        echo "  ${key}: slot ${KEY_SLOTS[$key]}"
    done
    echo ""

    read -p "Continue? (y/N) " confirm
    if [ "${confirm}" != "y" ]; then
        echo "Aborted"
        exit 0
    fi

    get_pin

    # Generate APK signing keys (need RSA for Android compatibility)
    for key in releasekey platform shared media networkstack sdk_sandbox bluetooth; do
        generate_yubikey_key "$key" "RSA4096"
        export_android_pubkey "$key"
    done

    # Generate AVB key (can use EC)
    generate_yubikey_key "avb" "ECCP384"
    export_android_pubkey "avb"

    create_pkcs11_config

    echo ""
    echo "=== Key Generation Complete ==="
    echo ""
    echo "Keys stored on YubiKey and public keys exported to:"
    echo "  ${KEYS_DIR}/${DEVICE}/"
    echo ""
    echo "IMPORTANT: The private keys NEVER leave the YubiKey!"
    echo "Back up your YubiKey's PIV management key and PIN securely."
}

# Sign APK using YubiKey
sign_apk_yubikey() {
    local input_apk="$1"
    local output_apk="$2"
    local key_name="${3:-releasekey}"
    local slot="${KEY_SLOTS[$key_name]}"

    echo "Signing APK with YubiKey (slot ${slot})..."

    get_pin

    # Use apksigner with PKCS#11
    apksigner sign \
        --ks NONE \
        --ks-type PKCS11 \
        --ks-key-alias "PIV AUTH key" \
        --provider-class sun.security.pkcs11.SunPKCS11 \
        --provider-arg "${KEYS_DIR}/${DEVICE}/yubikey-pkcs11.conf" \
        --ks-pass "pass:${YUBIKEY_PIN}" \
        --out "${output_apk}" \
        "${input_apk}"

    echo "Signed: ${output_apk}"
}

# Sign with step-kms (alternative method)
sign_with_step_kms() {
    local input_file="$1"
    local output_sig="$2"
    local key_name="${3:-releasekey}"
    local slot="${KEY_SLOTS[$key_name]}"

    echo "Signing with step-kms (slot ${slot})..."

    get_pin

    # Use a temporary file for PIN to avoid exposing it in process list
    local pin_file
    pin_file=$(mktemp)
    chmod 600 "${pin_file}"
    echo "${YUBIKEY_PIN}" > "${pin_file}"

    # step-kms can read PIN from file or environment
    YUBIKEY_PIN_FILE="${pin_file}" step kms sign \
        --kms "yubikey:slot-id=${slot}" \
        --in "${input_file}" \
        --out "${output_sig}"

    rm -f "${pin_file}"

    echo "Signature: ${output_sig}"
}

# Create signing wrapper for GrapheneOS build system
create_signing_wrapper() {
    echo "Creating GrapheneOS signing wrapper..."

    local wrapper_file="${KEYS_DIR}/${DEVICE}/sign_target_files_apks_yubikey.sh"

    cat > "${wrapper_file}" << 'WRAPPER_OUTER'
#!/bin/bash
set -euo pipefail

# GrapheneOS signing wrapper for YubiKey
# Usage: sign_target_files_apks_yubikey.sh <input_zip> <output_zip>

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/yubikey-signing.conf"

INPUT_ZIP="$1"
OUTPUT_ZIP="$2"

if [ -z "${YUBIKEY_PIN:-}" ]; then
    echo -n "Enter YubiKey PIN: "
    read -s YUBIKEY_PIN
    echo ""
fi

# Create temporary keystore config
PKCS11_CONF=$(mktemp)
cat > "${PKCS11_CONF}" << PKCS11_INNER
name = YubiKey
library = ${ANDROID_PK11_LIB}
slot = ${ANDROID_PK11_SLOT}
PKCS11_INNER

cd /src

# Create output directory if needed
mkdir -p "$(dirname "${OUTPUT_ZIP}")"

# Run sign_target_files_apks with PKCS#11
python3 build/tools/releasetools/sign_target_files_apks.py \
    -o \
    -d "${SCRIPT_DIR}" \
    "${INPUT_ZIP}" \
    "${OUTPUT_ZIP}"

rm -f "${PKCS11_CONF}"

echo "Signed: ${OUTPUT_ZIP}"
WRAPPER_OUTER

    chmod +x "${wrapper_file}"

    echo "Created signing wrapper at:"
    echo "  ${wrapper_file}"
}

# Show key info
show_key_info() {
    echo "=== YubiKey PIV Key Information ==="
    echo ""

    for key in "${!KEY_SLOTS[@]}"; do
        slot="${KEY_SLOTS[$key]}"
        echo "--- ${key} (slot ${slot}) ---"
        ykman piv certificates export "${slot}" - 2>/dev/null | \
            openssl x509 -noout -subject -dates 2>/dev/null || \
            echo "  (no certificate)"
        echo ""
    done
}

# Main
case "${1:-}" in
    generate)
        check_dependencies
        detect_yubikey
        generate_all_keys
        create_signing_wrapper
        ;;
    info)
        detect_yubikey
        show_key_info
        ;;
    sign-apk)
        check_dependencies
        detect_yubikey
        sign_apk_yubikey "${2:-}" "${3:-}" "${4:-releasekey}"
        ;;
    wrapper)
        create_signing_wrapper
        ;;
    *)
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  generate     Generate all signing keys on YubiKey"
        echo "  info         Show key information"
        echo "  sign-apk     Sign an APK (usage: sign-apk input.apk output.apk [keyname])"
        echo "  wrapper      Create GrapheneOS signing wrapper script"
        echo ""
        echo "Environment variables:"
        echo "  DEVICE              Target device (default: husky)"
        echo "  KEYS_DIR            Keys directory (default: /keys)"
        echo "  YUBIKEY_PIN         PIV PIN (will prompt if not set)"
        echo "  YUBIKEY_MGMT_KEY    Management key (default: factory)"
        echo "  STEP_CA_URL         step-ca server URL (optional)"
        echo "  STEP_CA_FINGERPRINT step-ca root fingerprint (optional)"
        ;;
esac

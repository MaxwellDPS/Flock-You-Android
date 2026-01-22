# GrapheneOS Build Environment for DGX Spark

Build GrapheneOS with Flock-You OEM integration on NVIDIA DGX Spark.

## Prerequisites

- NVIDIA DGX Spark with Docker and nvidia-container-runtime
- ~500GB free disk space for sources + build
- 128GB+ RAM recommended

## Quick Start

```bash
# 1. Create data directories
sudo mkdir -p /data/grapheneos/{src,out,ccache}
sudo chown -R $USER:$USER /data/grapheneos

# 2. Configure environment
cp .env.example .env
# Edit .env with your device and paths

# 3. Make scripts executable
chmod +x scripts/*.sh

# 4. Start the build container
docker compose up -d

# 5. Enter the container
docker compose exec grapheneos-builder bash

# 6. Inside container - sync sources
/scripts/sync.sh

# 7. Build Flock-You APK (optional - if not using prebuilt)
/scripts/build-oem-apk.sh

# 8. Build GrapheneOS with OEM integration
/scripts/build.sh

# 9. Generate signing keys (first time only)
/scripts/generate-keys.sh

# 10. Sign the build
/scripts/sign.sh
```

## Supported Devices

| Codename | Device |
|----------|--------|
| husky | Pixel 8 Pro |
| shiba | Pixel 8 |
| felix | Pixel Fold |
| tangorpro | Pixel Tablet |
| lynx | Pixel 7a |
| cheetah | Pixel 7 Pro |
| panther | Pixel 7 |

## OEM Integration

The build automatically integrates Flock-You as a privileged system app with:

- Pre-granted privacy permissions
- Privileged app whitelist
- SELinux policy
- Auto-start on boot
- Battery optimization exemption

### Customizing OEM Integration

Edit `scripts/oem-setup.sh` to customize:
- Additional permissions
- Default settings
- Branding

## DGX Spark Optimizations

This compose is tuned for DGX Spark:

- 64 parallel build jobs
- 256GB memory limit
- 100GB ccache
- 32GB tmpfs for intermediates
- GPU access for crypto operations
- Host networking for faster downloads

## Signing Keys

**IMPORTANT**: Keep your signing keys secure and backed up!

### Option 1: File-based Keys (Default)

Keys are stored in `./keys/{device}/`. Back them up immediately after generation.

```bash
/scripts/generate-keys.sh
```

### Option 2: YubiKey Hardware Signing (Recommended)

Use a YubiKey 5 for hardware-backed signing keys that never leave the device.

```bash
# Generate all keys on YubiKey
/scripts/yubikey-setup.sh generate

# View key info
/scripts/yubikey-setup.sh info

# Sign an APK manually
/scripts/yubikey-setup.sh sign-apk input.apk output.apk releasekey
```

**YubiKey PIV Slot Assignments:**
| Slot | Key |
|------|-----|
| 9a | releasekey |
| 9c | platform |
| 9d | shared |
| 9e | media |
| 82 | networkstack |
| 83 | sdk_sandbox |
| 84 | bluetooth |
| 85 | avb |

### Option 3: step-ca PKI Integration

For enterprise deployments, integrate with step-ca for centrally managed certificates:

```bash
export STEP_CA_URL=https://ca.example.com
export STEP_CA_FINGERPRINT=<fingerprint>
/scripts/yubikey-setup.sh generate
```

This issues certificates from your PKI instead of self-signed.

Lost keys = cannot push OTA updates to existing devices.

## Troubleshooting

### Out of memory during build
Reduce parallel jobs in `.env`:
```
MAKEFLAGS=-j32
```

### Repo sync failures
Reduce sync parallelism:
```
REPO_JOBS=8
```

### Build errors
Clean and retry:
```bash
cd /src
make clean
/scripts/build.sh
```

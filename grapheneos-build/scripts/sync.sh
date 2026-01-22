#!/bin/bash
set -euo pipefail

GRAPHENEOS_VERSION="${GRAPHENEOS_VERSION:-2025012200}"
GRAPHENEOS_BRANCH="${GRAPHENEOS_BRANCH:-15}"

echo "=== Syncing GrapheneOS sources ==="
echo "Version: ${GRAPHENEOS_VERSION}"
echo "Branch: ${GRAPHENEOS_BRANCH}"

cd /src

# Initialize repo if not already done
if [ ! -d ".repo" ]; then
    echo "Initializing repo..."
    repo init -u https://github.com/GrapheneOS/platform_manifest.git \
        -b refs/tags/${GRAPHENEOS_VERSION} \
        --depth=1 \
        --partial-clone \
        --clone-filter=blob:limit=10M
fi

# Sync with parallelism optimized for DGX Spark
echo "Syncing sources (this will take a while)..."
repo sync -c --no-clone-bundle --no-tags \
    -j${REPO_JOBS:-16} \
    --force-sync \
    --optimized-fetch

echo "=== Source sync complete ==="
du -sh /src

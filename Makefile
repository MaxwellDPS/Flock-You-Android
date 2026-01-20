# Flock You Android - Makefile
# ============================

.PHONY: help build build-debug build-release clean test lint \
        sideload sideload-release install uninstall \
        system-debug system-release oem-debug oem-release \
        logcat devices update-oui flipper-fap

# Default target
.DEFAULT_GOAL := help

# Configuration
GRADLE := ./gradlew
ADB := adb
PACKAGE := com.flockyou
PACKAGE_DEBUG := com.flockyou.debug

# APK paths
APK_SIDELOAD_DEBUG := app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
APK_SIDELOAD_RELEASE := app/build/outputs/apk/sideload/release/app-sideload-release.apk
APK_SYSTEM_DEBUG := app/build/outputs/apk/system/debug/app-system-debug.apk
APK_SYSTEM_RELEASE := app/build/outputs/apk/system/release/app-system-release.apk
APK_OEM_DEBUG := app/build/outputs/apk/oem/debug/app-oem-debug.apk
APK_OEM_RELEASE := app/build/outputs/apk/oem/release/app-oem-release.apk

# ============================================================================
# Help
# ============================================================================

help:
	@echo "Flock You Android - Build & Install Commands"
	@echo "============================================="
	@echo ""
	@echo "Build Commands:"
	@echo "  make build            Build sideload debug APK (default)"
	@echo "  make build-debug      Build sideload debug APK"
	@echo "  make build-release    Build sideload release APK"
	@echo "  make system-debug     Build system debug APK"
	@echo "  make system-release   Build system release APK"
	@echo "  make oem-debug        Build OEM debug APK"
	@echo "  make oem-release      Build OEM release APK"
	@echo "  make build-all        Build all variants"
	@echo ""
	@echo "Install Commands:"
	@echo "  make sideload         Build and install sideload debug APK via ADB"
	@echo "  make sideload-release Build and install sideload release APK via ADB"
	@echo "  make install          Alias for sideload"
	@echo "  make uninstall        Uninstall the app from connected device"
	@echo ""
	@echo "Development:"
	@echo "  make clean            Clean build artifacts"
	@echo "  make test             Run unit tests"
	@echo "  make lint             Run lint checks"
	@echo "  make logcat           Show app logs (filtered)"
	@echo "  make devices          List connected ADB devices"
	@echo ""
	@echo "Utilities:"
	@echo "  make update-oui       Update IEEE OUI database"
	@echo "  make flipper-fap      Build Flipper Zero FAP"
	@echo ""

# ============================================================================
# Build Commands
# ============================================================================

build: build-debug

build-debug:
	$(GRADLE) assembleSideloadDebug

build-release:
	$(GRADLE) assembleSideloadRelease

system-debug:
	$(GRADLE) assembleSystemDebug

system-release:
	$(GRADLE) assembleSystemRelease

oem-debug:
	$(GRADLE) assembleOemDebug

oem-release:
	$(GRADLE) assembleOemRelease

build-all:
	$(GRADLE) assemble

# ============================================================================
# Install Commands
# ============================================================================

sideload: build-debug
	@echo "Installing sideload debug APK..."
	$(ADB) install -r $(APK_SIDELOAD_DEBUG)
	@echo "Installed successfully. Launching app..."
	$(ADB) shell am start -n $(PACKAGE_DEBUG)/com.flockyou.MainActivity

sideload-release: build-release
	@echo "Installing sideload release APK..."
	$(ADB) install -r $(APK_SIDELOAD_RELEASE)
	@echo "Installed successfully. Launching app..."
	$(ADB) shell am start -n $(PACKAGE)/com.flockyou.MainActivity

install: sideload

uninstall:
	-$(ADB) uninstall $(PACKAGE_DEBUG)
	-$(ADB) uninstall $(PACKAGE)

# ============================================================================
# Testing & Quality
# ============================================================================

test:
	$(GRADLE) test

lint:
	$(GRADLE) lint

check: lint test

# ============================================================================
# Cleanup
# ============================================================================

clean:
	$(GRADLE) clean

# ============================================================================
# Development Utilities
# ============================================================================

devices:
	$(ADB) devices -l

logcat:
	$(ADB) logcat -v time | grep -E "(FlockYou|$(PACKAGE))"

logcat-all:
	$(ADB) logcat -v time *:V

# ============================================================================
# Asset & Build Tasks
# ============================================================================

update-oui:
	$(GRADLE) updateOuiDatabase

flipper-fap:
	$(GRADLE) prepareFlipperFap

flipper-install:
	$(GRADLE) installFlipperFap

# Flock Bridge Flipper App Makefile
# Builds the FAP (Flipper Application Package) for Flipper Zero

# Flipper firmware path - set this to your firmware location
FLIPPER_FIRMWARE ?= $(HOME)/flipper/flipperzero-firmware

# App configuration
APP_ID = flock_bridge
FAP_OUTPUT_DIR = dist

# Source files
SOURCES = \
	flock_bridge.c \
	helpers/usb_cdc.c \
	helpers/bt_serial.c \
	helpers/wips_engine.c \
	protocol/flock_protocol.c

HEADERS = \
	flock_bridge_app.h \
	helpers/usb_cdc.h \
	helpers/bt_serial.h \
	helpers/wips_engine.h \
	protocol/flock_protocol.h

# Default target
.PHONY: all
all: build

# Build the FAP using ufbt (recommended)
.PHONY: build
build:
	@echo "Building $(APP_ID) with ufbt..."
	ufbt

# Build using full firmware SDK (alternative)
.PHONY: build-sdk
build-sdk:
	@if [ ! -d "$(FLIPPER_FIRMWARE)" ]; then \
		echo "Error: Flipper firmware not found at $(FLIPPER_FIRMWARE)"; \
		echo "Set FLIPPER_FIRMWARE environment variable or install firmware"; \
		exit 1; \
	fi
	@echo "Building $(APP_ID) with firmware SDK..."
	cd $(FLIPPER_FIRMWARE) && ./fbt fap_$(APP_ID)

# Clean build artifacts
.PHONY: clean
clean:
	@echo "Cleaning build artifacts..."
	ufbt clean 2>/dev/null || true
	rm -rf $(FAP_OUTPUT_DIR)
	rm -rf .ufbt
	rm -rf build
	rm -f *.fap

# Install/deploy to connected Flipper
.PHONY: install
install: build
	@echo "Installing $(APP_ID) to Flipper..."
	ufbt launch

# Deploy without launching
.PHONY: deploy
deploy: build
	@echo "Deploying $(APP_ID) to Flipper..."
	ufbt install

# Run linter/static analysis
.PHONY: lint
lint:
	@echo "Running linter..."
	ufbt lint

# Format source code
.PHONY: format
format:
	@echo "Formatting source code..."
	ufbt format

# Create icons (if icons directory exists)
.PHONY: icons
icons:
	@if [ -d "icons" ]; then \
		echo "Processing icons..."; \
		ufbt icons; \
	else \
		echo "No icons directory found"; \
	fi

# Setup ufbt if not installed
.PHONY: setup
setup:
	@echo "Setting up ufbt..."
	@which ufbt > /dev/null 2>&1 || (echo "Installing ufbt..." && pip install ufbt)
	ufbt update

# Create distribution package
.PHONY: dist
dist: build
	@echo "Creating distribution package..."
	@mkdir -p $(FAP_OUTPUT_DIR)
	@cp -f .ufbt/build/$(APP_ID).fap $(FAP_OUTPUT_DIR)/ 2>/dev/null || \
		cp -f build/$(APP_ID).fap $(FAP_OUTPUT_DIR)/ 2>/dev/null || \
		echo "FAP file not found - check build output"
	@echo "Distribution package created in $(FAP_OUTPUT_DIR)/"

# Debug build
.PHONY: debug
debug:
	@echo "Building $(APP_ID) with debug symbols..."
	ufbt VERBOSE=1 DEBUG=1

# Show app info
.PHONY: info
info:
	@echo "=== Flock Bridge Flipper App ==="
	@echo "App ID:      $(APP_ID)"
	@echo "Sources:     $(SOURCES)"
	@echo "Headers:     $(HEADERS)"
	@echo ""
	@echo "Build commands:"
	@echo "  make build   - Build the FAP"
	@echo "  make install - Build and launch on Flipper"
	@echo "  make deploy  - Build and install (no launch)"
	@echo "  make clean   - Clean build artifacts"
	@echo "  make setup   - Setup ufbt build tool"
	@echo "  make dist    - Create distribution package"

# Watch for changes and rebuild
.PHONY: watch
watch:
	@echo "Watching for changes..."
	@while true; do \
		inotifywait -qr -e modify -e create -e delete \
			--include '.*\.(c|h)$$' . 2>/dev/null || \
		fswatch -1 -r --include='\.c$$' --include='\.h$$' . 2>/dev/null || \
		(echo "Install inotify-tools or fswatch for watch mode"; exit 1); \
		echo "Change detected, rebuilding..."; \
		$(MAKE) build; \
	done

# Generate compile_commands.json for IDE support
.PHONY: compiledb
compiledb:
	@echo "Generating compile_commands.json..."
	ufbt compiledb 2>/dev/null || \
		(cd $(FLIPPER_FIRMWARE) && ./fbt fap_$(APP_ID)_compiledb) 2>/dev/null || \
		echo "Could not generate compile_commands.json"

.PHONY: help
help: info

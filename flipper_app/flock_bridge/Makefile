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
	handlers/detection_callbacks.c \
	handlers/msg_handler.c \
	handlers/settings.c \
	handlers/probes/subghz/replay.c \
	handlers/probes/lf/probe.c \
	handlers/probes/ir/strobe.c \
	handlers/probes/wifi/probe.c \
	handlers/probes/ble/scan.c \
	handlers/probes/zigbee/beacon.c \
	handlers/probes/nrf24/inject.c \
	handlers/probes/gpio/pulse.c \
	handlers/probes/access/wiegand.c \
	handlers/probes/access/magspoof.c \
	handlers/probes/access/ibutton.c \
	helpers/usb_cdc.c \
	helpers/bt_serial.c \
	helpers/wips_engine.c \
	helpers/wips_detectors.c \
	helpers/external_radio.c \
	helpers/external_radio_protocol.c \
	protocol/proto_serialize.c \
	protocol/proto_parse.c \
	protocol/proto_parse_probes.c \
	scanners/scheduler/detection_scheduler.c \
	scanners/scheduler/sched_callbacks.c \
	scanners/scheduler/sched_thread.c \
	scanners/ble/ble_scanner.c \
	scanners/ble/ble_tracker_detect.c \
	scanners/subghz/subghz_scanner.c \
	scanners/subghz/subghz_decoder.c \
	scanners/wifi/wifi_scanner.c \
	scanners/wifi/wifi_deauth_detect.c \
	scanners/ir/ir_scanner.c \
	scanners/nfc/nfc_scanner.c

HEADERS = \
	flock_bridge_app.h \
	handlers/handlers.h \
	handlers/probes/probes.h \
	helpers/usb_cdc.h \
	helpers/bt_serial.h \
	helpers/wips_engine.h \
	helpers/wips_engine_internal.h \
	helpers/external_radio.h \
	helpers/external_radio_internal.h \
	protocol/flock_protocol.h \
	scanners/scanners.h \
	scanners/scheduler/detection_scheduler.h \
	scanners/scheduler/sched_internal.h \
	scanners/ble/ble_scanner.h \
	scanners/ble/ble_scanner_internal.h \
	scanners/subghz/subghz_scanner.h \
	scanners/subghz/subghz_internal.h \
	scanners/wifi/wifi_scanner.h \
	scanners/wifi/wifi_scanner_internal.h \
	scanners/ir/ir_scanner.h \
	scanners/nfc/nfc_scanner.h

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
	@echo "  make build     - Build the FAP"
	@echo "  make install   - Build and launch on Flipper"
	@echo "  make deploy    - Build and install (no launch)"
	@echo "  make clean     - Clean build artifacts"
	@echo "  make setup     - Setup ufbt build tool"
	@echo "  make dist      - Create distribution package"
	@echo ""
	@echo "Test commands:"
	@echo "  make test          - Run unit tests (no Flipper needed)"
	@echo "  make test-e2e      - Run e2e tests (Flipper required)"
	@echo "  make test-all      - Run all tests"
	@echo "  make test-cdc      - Test USB CDC communication"
	@echo "  make test-subghz-ble - Test SubGHz and BLE scanners"
	@echo "  make test-subghz   - Test SubGHz scanner only"
	@echo "  make test-ble      - Test BLE scanner only"
	@echo "  make test-coverage - Run tests with coverage"
	@echo "  make test-deps     - Install test dependencies"

# =============================================================================
# Testing
# =============================================================================

# Test configuration
TEST_DIR = tests
PYTEST = python3 -m pytest
PYTEST_OPTS = -v --tb=short

# Install test dependencies
.PHONY: test-deps
test-deps:
	@echo "Installing test dependencies..."
	pip3 install -r $(TEST_DIR)/requirements.txt

# Run unit tests (no Flipper required)
.PHONY: test-unit
test-unit:
	@echo "Running unit tests..."
	cd $(TEST_DIR) && $(PYTEST) unit/ $(PYTEST_OPTS)

# Run e2e tests (requires connected Flipper with FAP running)
.PHONY: test-e2e
test-e2e:
	@echo "Running e2e tests (Flipper required)..."
	cd $(TEST_DIR) && $(PYTEST) e2e/ $(PYTEST_OPTS) --flipper-required

# Run all tests (unit + e2e)
.PHONY: test-all
test-all:
	@echo "Running all tests..."
	cd $(TEST_DIR) && $(PYTEST) $(PYTEST_OPTS) --flipper-required

# Default test target (unit tests only)
.PHONY: test
test: test-unit

# Run tests with coverage report
.PHONY: test-coverage
test-coverage:
	@echo "Running tests with coverage..."
	cd $(TEST_DIR) && $(PYTEST) unit/ $(PYTEST_OPTS) --cov=. --cov-report=html --cov-report=term
	@echo "Coverage report generated in $(TEST_DIR)/htmlcov/"

# Run quick protocol test
.PHONY: test-protocol
test-protocol:
	@echo "Running protocol tests..."
	cd $(TEST_DIR) && $(PYTEST) unit/test_protocol.py $(PYTEST_OPTS)

# Test dual CDC communication (requires Flipper with FAP running)
.PHONY: test-cdc
test-cdc:
	@echo "Testing USB CDC communication..."
	cd $(TEST_DIR) && python3 test_dual_cdc.py

# Test SubGHz and BLE scanners (requires Flipper with FAP running)
.PHONY: test-subghz-ble
test-subghz-ble:
	@echo "Testing SubGHz and BLE scanners..."
	cd $(TEST_DIR) && $(PYTEST) e2e/test_subghz_ble.py $(PYTEST_OPTS) --flipper-required

# Test SubGHz scanner only (requires Flipper with FAP running)
.PHONY: test-subghz
test-subghz:
	@echo "Testing SubGHz scanner..."
	cd $(TEST_DIR) && $(PYTEST) e2e/test_subghz_ble.py::TestSubGhzScanner $(PYTEST_OPTS) --flipper-required

# Test BLE scanner only (requires Flipper with FAP running)
.PHONY: test-ble
test-ble:
	@echo "Testing BLE scanner..."
	cd $(TEST_DIR) && $(PYTEST) e2e/test_subghz_ble.py::TestBleScanner $(PYTEST_OPTS) --flipper-required

# Lint test code
.PHONY: test-lint
test-lint:
	@echo "Linting test code..."
	cd $(TEST_DIR) && python3 -m flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics
	cd $(TEST_DIR) && python3 -m flake8 . --count --exit-zero --max-complexity=10 --max-line-length=120 --statistics

# =============================================================================
# Watch and Development
# =============================================================================

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

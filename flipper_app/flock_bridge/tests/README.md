# Flock Bridge FAP Test Suite

Comprehensive end-to-end testing framework for the Flock Bridge Flipper Application Package (FAP).

## Overview

This test suite includes:

- **Unit Tests**: Protocol encoding/decoding tests that run without hardware
- **E2E Tests**: Full communication tests that require a connected Flipper Zero
- **Stress Tests**: Load and reliability testing
- **Security Tests**: Input validation and error handling tests

## Quick Start

### Install Dependencies

```bash
cd tests
pip install -r requirements.txt
```

### Run Unit Tests (No Flipper Required)

```bash
python run_tests.py unit
```

### Run E2E Tests (Flipper Required)

1. Connect your Flipper Zero via USB
2. Start the Flock Bridge FAP on the Flipper
3. Run the tests:

```bash
python run_tests.py e2e --flipper-required
```

### Detect Flipper

Check if your Flipper is properly connected:

```bash
python run_tests.py detect
```

## Test Structure

```
tests/
├── __init__.py
├── conftest.py              # Pytest configuration and fixtures
├── pytest.ini               # Pytest settings
├── requirements.txt         # Python dependencies
├── run_tests.py            # Test runner script
├── README.md               # This file
├── flock_protocol.py       # Python protocol implementation
├── flipper_connection.py   # USB CDC connection handler
├── fixtures/
│   ├── __init__.py
│   └── sample_data.py      # Sample test data
├── unit/
│   ├── __init__.py
│   └── test_protocol.py    # Protocol unit tests
└── e2e/
    ├── __init__.py
    └── test_fap_communication.py  # E2E communication tests
```

## Running Tests

### Using pytest directly

```bash
# Run all tests
pytest

# Run unit tests only
pytest unit/

# Run with verbose output
pytest -vv

# Run specific test file
pytest unit/test_protocol.py

# Run tests matching a keyword
pytest -k "heartbeat"

# Run with coverage
pytest --cov=. --cov-report=html
```

### Using the test runner

```bash
# Run unit tests
python run_tests.py unit

# Run unit tests with verbose output
python run_tests.py unit -v

# Run E2E tests with specific Flipper port
python run_tests.py e2e --flipper-port /dev/ttyACM0

# Run all tests with coverage
python run_tests.py all --coverage

# Run specific test file
python run_tests.py file unit/test_protocol.py
```

## Test Categories

### Unit Tests (`tests/unit/`)

Protocol tests that verify encoding/decoding without hardware:

- `TestFlockMessageHeader`: Header pack/unpack
- `TestFlockProtocolMessages`: Message creation/parsing
- `TestFlockStatusResponse`: Status response handling
- `TestFlockWifiNetwork`: WiFi data structures
- `TestFlockSubGhzDetection`: Sub-GHz data structures
- `TestFlockMessageBuffer`: Stream buffer processing

### E2E Tests (`tests/e2e/`)

Full communication tests with real Flipper:

- `TestFlipperConnection`: Connection management
- `TestHeartbeat`: Heartbeat functionality
- `TestStatusRequest`: Status request/response
- `TestScanRequests`: Scan command handling
- `TestActiveProbes`: Active probe commands
- `TestErrorHandling`: Error and validation
- `TestStress`: Load testing
- `TestMessageOrdering`: Sequencing
- `TestProtocolCompliance`: Protocol compliance

## Writing New Tests

### Unit Test Example

```python
def test_heartbeat_message():
    msg = FlockProtocol.create_heartbeat()
    assert len(msg) == FLOCK_HEADER_SIZE
    header = FlockMessageHeader.unpack(msg)
    assert header.msg_type == FlockMessageType.HEARTBEAT
```

### E2E Test Example

```python
@pytest.mark.flipper_required
def test_status_response(flipper_connection):
    msg = FlockProtocol.create_status_request()
    flipper_connection.send(msg)
    response = flipper_connection.receive(timeout=5.0)
    assert response is not None
    header, payload = response
    assert header.msg_type == FlockMessageType.STATUS_RESPONSE
```

## Fixtures

Available pytest fixtures:

- `flipper_connection`: Module-scoped Flipper connection
- `test_client`: High-level test client
- `sample_wifi_networks`: Sample WiFi data
- `sample_subghz_detections`: Sample Sub-GHz data
- `sample_nfc_detections`: Sample NFC data

## Markers

- `@pytest.mark.flipper_required`: Test requires connected Flipper
- `@pytest.mark.slow`: Slow running test
- `@pytest.mark.timeout(seconds)`: Test timeout

## Troubleshooting

### Flipper Not Detected

1. Make sure Flipper is connected via USB
2. Check that Flock Bridge FAP is running
3. Verify USB permissions (Linux: add user to `dialout` group)
4. Run `python run_tests.py detect` to debug

### Connection Timeout

1. Restart the FAP on Flipper
2. Unplug and replug USB cable
3. Check for other applications using the serial port

### Test Failures

1. Check Flipper is running the latest FAP build
2. Verify protocol version matches
3. Check test output for specific error messages

## CI/CD Integration

Example GitHub Actions workflow:

```yaml
name: Test FAP Protocol

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-python@v4
        with:
          python-version: '3.10'
      - name: Install dependencies
        run: pip install -r flipper_app/flock_bridge/tests/requirements.txt
      - name: Run unit tests
        run: python run_tests.py unit
        working-directory: flipper_app/flock_bridge/tests
```

## Protocol Reference

The Python protocol implementation in `flock_protocol.py` mirrors the C implementation and can be used as a reference:

- Message types: `FlockMessageType` enum
- Error codes: `FlockErrorCode` enum
- Data structures: `FlockWifiNetwork`, `FlockSubGhzDetection`, etc.
- Protocol encoder: `FlockProtocol` class
- Stream buffer: `FlockMessageBuffer` class

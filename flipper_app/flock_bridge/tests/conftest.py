"""
Pytest configuration for Flock Bridge FAP tests.

This file configures pytest with custom markers, fixtures, and command-line options.
"""

import pytest
import logging
import sys
from pathlib import Path

# Add tests directory to path
sys.path.insert(0, str(Path(__file__).parent))


def pytest_addoption(parser):
    """Add custom command-line options."""
    parser.addoption(
        "--flipper-port",
        action="store",
        default=None,
        help="Serial port for Flipper Zero (auto-detect if not specified)"
    )
    parser.addoption(
        "--flipper-required",
        action="store_true",
        default=False,
        help="Run tests that require a connected Flipper Zero"
    )
    parser.addoption(
        "--log-protocol",
        action="store_true",
        default=False,
        help="Enable verbose protocol logging"
    )


def pytest_configure(config):
    """Configure pytest with custom markers."""
    config.addinivalue_line(
        "markers",
        "flipper_required: mark test as requiring a connected Flipper Zero"
    )
    config.addinivalue_line(
        "markers",
        "slow: mark test as slow running"
    )

    # Configure logging
    if config.getoption("--log-protocol"):
        logging.basicConfig(
            level=logging.DEBUG,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        logging.getLogger("flock_protocol").setLevel(logging.DEBUG)
        logging.getLogger("flipper_connection").setLevel(logging.DEBUG)


def pytest_collection_modifyitems(config, items):
    """Modify test collection based on options."""
    if not config.getoption("--flipper-required"):
        # Skip flipper_required tests if flag not provided
        skip_flipper = pytest.mark.skip(reason="need --flipper-required option to run")
        for item in items:
            if "flipper_required" in item.keywords:
                item.add_marker(skip_flipper)


@pytest.fixture(scope="session")
def flipper_port(request):
    """Get Flipper port from command line or auto-detect."""
    port = request.config.getoption("--flipper-port")
    if port:
        return port

    # Auto-detect
    from flipper_connection import FlipperConnection
    return FlipperConnection.find_flipper_port()


@pytest.fixture(scope="session")
def flipper_available(flipper_port):
    """Check if Flipper is available."""
    return flipper_port is not None


@pytest.fixture(scope="module")
def flipper_connection(flipper_port, flipper_available):
    """Create module-scoped Flipper connection."""
    if not flipper_available:
        pytest.skip("Flipper Zero not connected")

    from flipper_connection import FlipperConnection, FlipperConnectionError

    conn = FlipperConnection(port=flipper_port, auto_reconnect=False)
    try:
        conn.connect()
        yield conn
    except FlipperConnectionError as e:
        pytest.skip(f"Failed to connect to Flipper: {e}")
    finally:
        conn.disconnect()


@pytest.fixture
def test_client(flipper_connection):
    """Create test client with existing connection."""
    from flipper_connection import FlipperTestClient
    return FlipperTestClient(flipper_connection)


# ============================================================================
# Test fixtures for protocol testing
# ============================================================================

@pytest.fixture
def sample_wifi_networks():
    """Sample WiFi network data for testing."""
    from flock_protocol import FlockWifiNetwork, WifiSecurityType
    return [
        FlockWifiNetwork(
            ssid="TestNetwork1",
            bssid=bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x01]),
            rssi=-50,
            channel=1,
            security=WifiSecurityType.WPA2,
            hidden=False
        ),
        FlockWifiNetwork(
            ssid="TestNetwork2",
            bssid=bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x02]),
            rssi=-65,
            channel=6,
            security=WifiSecurityType.WPA3,
            hidden=False
        ),
        FlockWifiNetwork(
            ssid="HiddenNet",
            bssid=bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0x03]),
            rssi=-80,
            channel=11,
            security=WifiSecurityType.WPA2,
            hidden=True
        ),
    ]


@pytest.fixture
def sample_subghz_detections():
    """Sample Sub-GHz detection data for testing."""
    from flock_protocol import FlockSubGhzDetection, SubGhzModulation
    return [
        FlockSubGhzDetection(
            frequency=433920000,
            rssi=-45,
            modulation=SubGhzModulation.OOK,
            duration_ms=150,
            bandwidth=500000,
            protocol_id=1,
            protocol_name="Princeton"
        ),
        FlockSubGhzDetection(
            frequency=315000000,
            rssi=-60,
            modulation=SubGhzModulation.FSK,
            duration_ms=200,
            bandwidth=250000,
            protocol_id=5,
            protocol_name="CAME"
        ),
    ]


@pytest.fixture
def sample_nfc_detections():
    """Sample NFC detection data for testing."""
    from flock_protocol import FlockNfcDetection
    return [
        FlockNfcDetection(
            uid=bytes([0x04, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC] + [0] * 3),
            uid_len=7,
            nfc_type=1,
            sak=0x08,
            atqa=bytes([0x04, 0x00]),
            type_name="MIFARE Classic"
        ),
        FlockNfcDetection(
            uid=bytes([0x04, 0x11, 0x22, 0x33] + [0] * 6),
            uid_len=4,
            nfc_type=1,
            sak=0x00,
            atqa=bytes([0x44, 0x00]),
            type_name="Ultralight"
        ),
    ]

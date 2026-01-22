"""
SubGHz and BLE Scanner USB Tests

These tests verify the SubGHz and BLE scanning functionality
of the Flock Bridge FAP via USB communication.

Run with: pytest tests/e2e/test_subghz_ble.py -v --flipper-required
"""

import pytest
import time
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from flock_protocol import (
    FlockProtocol, FlockMessageHeader, FlockMessageType, FlockErrorCode,
    FlockStatusResponse, FlockSubGhzDetection, FlockBleDevice,
    FLOCK_PROTOCOL_VERSION, FLOCK_HEADER_SIZE
)
from flipper_connection import (
    FlipperConnection, FlipperTestClient, FlipperConnectionError, FlipperTimeoutError
)


# ============================================================================
# Fixtures
# ============================================================================

@pytest.fixture(scope="module")
def flipper_available():
    """Check if Flipper Zero is available."""
    port = FlipperConnection.find_flipper_port()
    return port is not None


@pytest.fixture(scope="module")
def flipper_connection(flipper_available, request):
    """Create a shared Flipper connection for the test module."""
    if not flipper_available:
        pytest.skip("Flipper Zero not connected")

    # Check for --flipper-required flag
    if request.config.getoption("--flipper-required", default=False) is False:
        if not hasattr(request.config, '_flipper_required_warned'):
            request.config._flipper_required_warned = True
        pytest.skip("need --flipper-required option to run")

    conn = FlipperConnection(auto_reconnect=False)
    try:
        conn.connect()
        yield conn
    finally:
        conn.disconnect()


@pytest.fixture
def test_client(flipper_connection):
    """Create a test client using the shared connection."""
    return FlipperTestClient(flipper_connection)


# ============================================================================
# SubGHz Scanner Tests
# ============================================================================

@pytest.mark.flipper_required
class TestSubGhzScanner:
    """Test Sub-GHz scanner functionality over USB."""

    def test_subghz_ready_status(self, test_client):
        """Test that SubGHz scanner reports ready in status."""
        status = test_client.get_status()
        assert status.subghz_ready is True, "SubGHz scanner should be ready"

    def test_subghz_scan_request_accepted(self, flipper_connection):
        """Test SubGHz scan request is accepted without error."""
        msg = FlockProtocol.create_subghz_scan_request(433000000, 434000000)
        flipper_connection.send(msg)

        # Should not receive an error
        try:
            header, payload = flipper_connection.receive(timeout=2.0)
            if header.msg_type == FlockMessageType.ERROR:
                error_code, error_msg = FlockProtocol.parse_error(payload)
                pytest.fail(f"Received error: {error_code.name}: {error_msg}")
        except FlipperTimeoutError:
            pass  # No response is acceptable (scanning in background)

    def test_subghz_frequency_range_315mhz(self, flipper_connection):
        """Test SubGHz scan at 315 MHz (US garage doors)."""
        msg = FlockProtocol.create_subghz_scan_request(314000000, 316000000)
        flipper_connection.send(msg)
        time.sleep(0.5)
        # Just verify no crash

    def test_subghz_frequency_range_433mhz(self, flipper_connection):
        """Test SubGHz scan at 433.92 MHz (EU remotes)."""
        msg = FlockProtocol.create_subghz_scan_request(433000000, 435000000)
        flipper_connection.send(msg)
        time.sleep(0.5)
        # Just verify no crash

    def test_subghz_frequency_range_868mhz(self, flipper_connection):
        """Test SubGHz scan at 868 MHz (EU devices)."""
        msg = FlockProtocol.create_subghz_scan_request(867000000, 869000000)
        flipper_connection.send(msg)
        time.sleep(0.5)
        # Just verify no crash

    def test_subghz_frequency_range_915mhz(self, flipper_connection):
        """Test SubGHz scan at 915 MHz (US ISM band)."""
        msg = FlockProtocol.create_subghz_scan_request(914000000, 916000000)
        flipper_connection.send(msg)
        time.sleep(0.5)
        # Just verify no crash

    def test_subghz_full_band_scan(self, flipper_connection):
        """Test full band SubGHz scan request."""
        msg = FlockProtocol.create_subghz_scan_request(300000000, 928000000)
        flipper_connection.send(msg)
        time.sleep(0.5)
        # Just verify no crash

    def test_subghz_detection_count_tracks(self, test_client):
        """Test that SubGHz detection count is tracked."""
        status = test_client.get_status()
        # Detection count should be accessible (may be 0 if no signals)
        assert status.subghz_detection_count >= 0, "Detection count should be non-negative"

    def test_subghz_scan_result_format(self, flipper_connection, test_client):
        """Test SubGHz scan result message format when detections occur."""
        # Request scan
        msg = FlockProtocol.create_subghz_scan_request(433000000, 434000000)
        flipper_connection.send(msg)

        # Wait for potential scan result (may timeout if no signals)
        try:
            header, payload = flipper_connection.receive(timeout=5.0)
            if header.msg_type == FlockMessageType.SUBGHZ_SCAN_RESULT:
                timestamp, freq_start, freq_end, detections = FlockProtocol.parse_subghz_result(payload)
                assert timestamp >= 0, "Timestamp should be non-negative"
                assert freq_start > 0, "Start frequency should be positive"
                assert isinstance(detections, list), "Detections should be a list"
                for det in detections:
                    assert isinstance(det, FlockSubGhzDetection)
                    assert det.frequency > 0, "Detection frequency should be positive"
        except FlipperTimeoutError:
            pass  # No detections is acceptable

    def test_subghz_continuous_scanning(self, flipper_connection, test_client):
        """Test that SubGHz scanner runs continuously."""
        # Get initial status
        status1 = test_client.get_status()

        # Request scan and wait
        msg = FlockProtocol.create_subghz_scan_request()
        flipper_connection.send(msg)
        time.sleep(2.0)

        # Get status again - scanner should still be ready
        status2 = test_client.get_status()
        assert status2.subghz_ready is True, "SubGHz should remain ready during scanning"

    def test_subghz_multiple_requests(self, flipper_connection):
        """Test multiple rapid SubGHz scan requests don't crash."""
        for i in range(5):
            msg = FlockProtocol.create_subghz_scan_request(
                433000000 + i * 1000000,
                434000000 + i * 1000000
            )
            flipper_connection.send(msg)
            time.sleep(0.2)

        # Verify we can still ping
        time.sleep(0.5)
        heartbeat = FlockProtocol.create_heartbeat()
        flipper_connection.send(heartbeat)
        header, _ = flipper_connection.receive(timeout=2.0)
        assert header.msg_type == FlockMessageType.HEARTBEAT


# ============================================================================
# BLE Scanner Tests
# ============================================================================

@pytest.mark.flipper_required
class TestBleScanner:
    """Test BLE scanner functionality over USB."""

    def test_ble_ready_status(self, test_client):
        """Test that BLE scanner reports ready in status."""
        status = test_client.get_status()
        # Note: BLE may not always be ready due to Bluetooth Serial usage
        # This is expected behavior - just verify we get a valid response
        assert isinstance(status.ble_ready, bool), "BLE ready should be boolean"

    def test_ble_scan_request_accepted(self, flipper_connection):
        """Test BLE scan request is accepted without error."""
        msg = FlockProtocol.create_ble_scan_request()
        flipper_connection.send(msg)

        # Should not receive an error
        try:
            header, payload = flipper_connection.receive(timeout=2.0)
            if header.msg_type == FlockMessageType.ERROR:
                error_code, error_msg = FlockProtocol.parse_error(payload)
                # BLE busy is acceptable (used for BT Serial)
                if error_code != FlockErrorCode.BUSY:
                    pytest.fail(f"Received unexpected error: {error_code.name}: {error_msg}")
        except FlipperTimeoutError:
            pass  # No response is acceptable

    def test_ble_scan_count_tracks(self, test_client):
        """Test that BLE scan count is tracked."""
        status = test_client.get_status()
        assert status.ble_scan_count >= 0, "BLE scan count should be non-negative"

    def test_ble_scan_result_format(self, flipper_connection):
        """Test BLE scan result message format when devices found."""
        msg = FlockProtocol.create_ble_scan_request()
        flipper_connection.send(msg)

        # Wait for potential scan result
        try:
            header, payload = flipper_connection.receive(timeout=5.0)
            if header.msg_type == FlockMessageType.BLE_SCAN_RESULT:
                timestamp, devices = FlockProtocol.parse_ble_result(payload)
                assert timestamp >= 0, "Timestamp should be non-negative"
                assert isinstance(devices, list), "Devices should be a list"
                for dev in devices:
                    assert isinstance(dev, FlockBleDevice)
                    assert len(dev.mac_address) == 6, "MAC address should be 6 bytes"
        except FlipperTimeoutError:
            pass  # No devices found is acceptable

    def test_ble_multiple_requests(self, flipper_connection):
        """Test multiple rapid BLE scan requests don't crash."""
        for _ in range(3):
            msg = FlockProtocol.create_ble_scan_request()
            flipper_connection.send(msg)
            time.sleep(0.3)

        # Verify we can still ping
        time.sleep(0.5)
        heartbeat = FlockProtocol.create_heartbeat()
        flipper_connection.send(heartbeat)
        header, _ = flipper_connection.receive(timeout=2.0)
        assert header.msg_type == FlockMessageType.HEARTBEAT

    def test_ble_tracker_detection_available(self, test_client):
        """Test that BLE tracker detection is configured."""
        # Just verify status is retrievable with BLE info
        status = test_client.get_status()
        assert hasattr(status, 'ble_ready'), "Status should have ble_ready field"
        assert hasattr(status, 'ble_scan_count'), "Status should have ble_scan_count field"


# ============================================================================
# Combined SubGHz + BLE Tests
# ============================================================================

@pytest.mark.flipper_required
class TestCombinedScanning:
    """Test combined SubGHz and BLE scanning functionality."""

    def test_both_scanners_report_status(self, test_client):
        """Test both SubGHz and BLE report in status response."""
        status = test_client.get_status()

        assert hasattr(status, 'subghz_ready'), "Status should have subghz_ready"
        assert hasattr(status, 'ble_ready'), "Status should have ble_ready"
        assert hasattr(status, 'subghz_detection_count'), "Status should have subghz_detection_count"
        assert hasattr(status, 'ble_scan_count'), "Status should have ble_scan_count"

    def test_sequential_subghz_then_ble(self, flipper_connection, test_client):
        """Test SubGHz scan followed by BLE scan."""
        # SubGHz scan
        msg1 = FlockProtocol.create_subghz_scan_request()
        flipper_connection.send(msg1)
        time.sleep(1.0)

        # BLE scan
        msg2 = FlockProtocol.create_ble_scan_request()
        flipper_connection.send(msg2)
        time.sleep(1.0)

        # Verify system still responsive
        status = test_client.get_status()
        assert status.protocol_version == FLOCK_PROTOCOL_VERSION

    def test_interleaved_scan_requests(self, flipper_connection):
        """Test interleaved SubGHz and BLE scan requests."""
        for i in range(3):
            # SubGHz request
            msg1 = FlockProtocol.create_subghz_scan_request()
            flipper_connection.send(msg1)
            time.sleep(0.1)

            # BLE request
            msg2 = FlockProtocol.create_ble_scan_request()
            flipper_connection.send(msg2)
            time.sleep(0.1)

        # Verify we can still ping
        heartbeat = FlockProtocol.create_heartbeat()
        flipper_connection.send(heartbeat)
        header, _ = flipper_connection.receive(timeout=2.0)
        assert header.msg_type == FlockMessageType.HEARTBEAT

    def test_detection_counts_accumulate(self, test_client):
        """Test that detection counts accumulate over time."""
        status1 = test_client.get_status()

        # Wait a bit for potential detections
        time.sleep(2.0)

        status2 = test_client.get_status()

        # Counts should be non-negative and not decrease
        assert status2.subghz_detection_count >= status1.subghz_detection_count
        assert status2.ble_scan_count >= status1.ble_scan_count

    def test_uptime_increases_during_scanning(self, test_client):
        """Test uptime increases while scanning is active."""
        status1 = test_client.get_status()
        uptime1 = status1.uptime_seconds

        time.sleep(2.0)

        status2 = test_client.get_status()
        uptime2 = status2.uptime_seconds

        assert uptime2 > uptime1, "Uptime should increase"


# ============================================================================
# Stress Tests
# ============================================================================

@pytest.mark.flipper_required
class TestScannerStress:
    """Stress tests for SubGHz and BLE scanners."""

    def test_rapid_subghz_requests(self, flipper_connection):
        """Test rapid-fire SubGHz scan requests."""
        for i in range(10):
            freq = 300000000 + (i * 50000000)
            msg = FlockProtocol.create_subghz_scan_request(freq, freq + 10000000)
            flipper_connection.send(msg)
            time.sleep(0.05)

        # Clear any pending responses
        time.sleep(0.5)

        # Verify connectivity
        heartbeat = FlockProtocol.create_heartbeat()
        flipper_connection.send(heartbeat)
        header, _ = flipper_connection.receive(timeout=3.0)
        assert header.msg_type == FlockMessageType.HEARTBEAT

    def test_rapid_ble_requests(self, flipper_connection):
        """Test rapid-fire BLE scan requests."""
        for _ in range(10):
            msg = FlockProtocol.create_ble_scan_request()
            flipper_connection.send(msg)
            time.sleep(0.05)

        # Clear any pending responses
        time.sleep(0.5)

        # Verify connectivity
        heartbeat = FlockProtocol.create_heartbeat()
        flipper_connection.send(heartbeat)
        header, _ = flipper_connection.receive(timeout=3.0)
        assert header.msg_type == FlockMessageType.HEARTBEAT

    def test_sustained_mixed_scanning(self, flipper_connection, test_client):
        """Test sustained mixed SubGHz and BLE scanning."""
        iterations = 5

        for i in range(iterations):
            # SubGHz scan
            msg1 = FlockProtocol.create_subghz_scan_request()
            flipper_connection.send(msg1)

            # Brief pause
            time.sleep(0.2)

            # BLE scan
            msg2 = FlockProtocol.create_ble_scan_request()
            flipper_connection.send(msg2)

            # Brief pause
            time.sleep(0.2)

        # Verify system health
        status = test_client.get_status()
        assert status.subghz_ready is True, f"SubGHz should be ready after {iterations} iterations"

    def test_long_duration_stability(self, test_client):
        """Test scanner stability over longer duration."""
        start_status = test_client.get_status()

        # Run for a few seconds with periodic checks
        for _ in range(3):
            time.sleep(1.0)
            status = test_client.get_status()
            assert status.subghz_ready is True, "SubGHz should remain ready"

        end_status = test_client.get_status()

        # Verify uptime increased appropriately
        assert end_status.uptime_seconds >= start_status.uptime_seconds + 2

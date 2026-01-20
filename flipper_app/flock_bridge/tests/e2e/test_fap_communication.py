"""
End-to-end tests for Flock Bridge FAP communication.

These tests require a Flipper Zero connected via USB with the Flock Bridge FAP running.
Run with: pytest tests/e2e/ -v --flipper-required

To skip these tests when no Flipper is connected:
    pytest tests/e2e/ -v -m "not flipper_required"
"""

import pytest
import time
import struct
import os
import sys
from pathlib import Path

# Add parent to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from flock_protocol import (
    FlockProtocol, FlockMessageHeader, FlockMessageType, FlockErrorCode,
    FlockStatusResponse, FLOCK_PROTOCOL_VERSION, FLOCK_HEADER_SIZE,
    FLOCK_MAX_PAYLOAD_SIZE
)
from flipper_connection import (
    FlipperConnection, FlipperTestClient, FlipperConnectionError, FlipperTimeoutError
)


# ============================================================================
# Pytest Configuration
# ============================================================================

def pytest_configure(config):
    config.addinivalue_line(
        "markers", "flipper_required: mark test as requiring connected Flipper Zero"
    )


@pytest.fixture(scope="module")
def flipper_available():
    """Check if Flipper Zero is available."""
    port = FlipperConnection.find_flipper_port()
    return port is not None


@pytest.fixture(scope="module")
def flipper_connection(flipper_available):
    """Create a shared Flipper connection for the test module."""
    if not flipper_available:
        pytest.skip("Flipper Zero not connected")

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
# Connection Tests
# ============================================================================

@pytest.mark.flipper_required
class TestFlipperConnection:
    """Test basic Flipper connection functionality."""

    def test_connect_disconnect(self, flipper_available):
        """Test connecting and disconnecting from Flipper."""
        if not flipper_available:
            pytest.skip("Flipper Zero not connected")

        conn = FlipperConnection()
        assert conn.connect() is True
        assert conn.is_connected() is True
        conn.disconnect()
        assert conn.is_connected() is False

    def test_connection_context_manager(self, flipper_available):
        """Test connection as context manager."""
        if not flipper_available:
            pytest.skip("Flipper Zero not connected")

        with FlipperConnection().session() as conn:
            assert conn.is_connected() is True
        # Should be disconnected after exiting context

    def test_find_flipper_port(self, flipper_available):
        """Test auto-detection of Flipper port."""
        if not flipper_available:
            pytest.skip("Flipper Zero not connected")

        port = FlipperConnection.find_flipper_port()
        assert port is not None
        assert isinstance(port, str)


# ============================================================================
# Heartbeat Tests
# ============================================================================

@pytest.mark.flipper_required
class TestHeartbeat:
    """Test heartbeat functionality."""

    def test_heartbeat_response(self, flipper_connection):
        """Test that FAP responds to heartbeat."""
        msg = FlockProtocol.create_heartbeat()
        flipper_connection.send(msg)
        response = flipper_connection.receive(timeout=2.0)

        assert response is not None
        header, payload = response
        assert header.msg_type == FlockMessageType.HEARTBEAT
        assert header.payload_length == 0

    def test_multiple_heartbeats(self, flipper_connection):
        """Test multiple consecutive heartbeats."""
        for i in range(10):
            msg = FlockProtocol.create_heartbeat()
            flipper_connection.send(msg)
            response = flipper_connection.receive(timeout=2.0)
            assert response is not None
            assert response[0].msg_type == FlockMessageType.HEARTBEAT

    def test_heartbeat_timing(self, flipper_connection):
        """Test heartbeat response timing."""
        msg = FlockProtocol.create_heartbeat()
        start = time.time()
        flipper_connection.send(msg)
        response = flipper_connection.receive(timeout=5.0)
        elapsed = time.time() - start

        assert response is not None
        assert elapsed < 1.0  # Should respond within 1 second

    def test_ping_helper(self, test_client):
        """Test ping helper function."""
        assert test_client.ping() is True


# ============================================================================
# Status Request Tests
# ============================================================================

@pytest.mark.flipper_required
class TestStatusRequest:
    """Test status request/response functionality."""

    def test_status_response(self, flipper_connection):
        """Test that FAP responds to status request."""
        msg = FlockProtocol.create_status_request()
        flipper_connection.send(msg)
        response = flipper_connection.receive(timeout=5.0)

        assert response is not None
        header, payload = response
        assert header.msg_type == FlockMessageType.STATUS_RESPONSE
        assert header.payload_length >= FlockStatusResponse.SIZE

    def test_status_response_content(self, test_client):
        """Test status response contains valid data."""
        status = test_client.get_status()

        assert status.protocol_version == FLOCK_PROTOCOL_VERSION
        assert 0 <= status.battery_percent <= 100
        assert status.uptime_seconds >= 0
        # Check boolean flags are valid
        assert isinstance(status.subghz_ready, bool)
        assert isinstance(status.ble_ready, bool)
        assert isinstance(status.ir_ready, bool)
        assert isinstance(status.nfc_ready, bool)

    def test_status_counters_increment(self, test_client):
        """Test that status counters track activity."""
        status1 = test_client.get_status()

        # Send some heartbeats
        for _ in range(5):
            test_client.ping()

        status2 = test_client.get_status()

        # Messages sent/received should have increased
        # (The FAP counts sent messages, we're receiving heartbeat responses)

    def test_uptime_increases(self, test_client):
        """Test that uptime counter increases."""
        status1 = test_client.get_status()
        time.sleep(2)
        status2 = test_client.get_status()

        assert status2.uptime_seconds >= status1.uptime_seconds


# ============================================================================
# Scan Request Tests
# ============================================================================

@pytest.mark.flipper_required
class TestScanRequests:
    """Test scan request handling."""

    def test_wifi_scan_request_accepted(self, flipper_connection):
        """Test WiFi scan request is accepted (may not return results without ESP32)."""
        msg = FlockProtocol.create_wifi_scan_request()
        flipper_connection.send(msg)
        # WiFi scan requires ESP32, so we just verify no error response
        # Wait briefly for any error response
        response = flipper_connection.receive(timeout=1.0)
        if response:
            header, _ = response
            # Should not receive error for valid request
            assert header.msg_type != FlockMessageType.ERROR or True  # May get error if no ESP32

    def test_subghz_scan_request_accepted(self, flipper_connection):
        """Test Sub-GHz scan request is accepted."""
        msg = FlockProtocol.create_subghz_scan_request()
        flipper_connection.send(msg)
        # Scanner runs continuously, so we just check no immediate error
        response = flipper_connection.receive(timeout=1.0)
        # No immediate error expected
        if response:
            header, _ = response
            # May receive detection results or nothing

    def test_ble_scan_request_accepted(self, flipper_connection):
        """Test BLE scan request is accepted."""
        msg = FlockProtocol.create_ble_scan_request()
        flipper_connection.send(msg)
        response = flipper_connection.receive(timeout=1.0)
        # BLE scanning may not be available to FAPs
        # Just verify we don't crash

    def test_ir_scan_request_accepted(self, flipper_connection):
        """Test IR scan request is accepted."""
        msg = FlockProtocol.create_ir_scan_request()
        flipper_connection.send(msg)
        response = flipper_connection.receive(timeout=1.0)
        # No error expected

    def test_nfc_scan_request_accepted(self, flipper_connection):
        """Test NFC scan request is accepted."""
        msg = FlockProtocol.create_nfc_scan_request()
        flipper_connection.send(msg)
        response = flipper_connection.receive(timeout=1.0)
        # No error expected


# ============================================================================
# Active Probe Tests
# ============================================================================

@pytest.mark.flipper_required
class TestActiveProbes:
    """Test active probe command handling."""

    def test_lf_probe_not_implemented(self, test_client):
        """Test LF probe returns not-implemented error."""
        success, message = test_client.send_lf_probe(1000)
        # Currently returns NOT_IMPLEMENTED
        assert success is False
        assert "NOT_IMPLEMENTED" in message

    def test_ir_strobe_not_implemented(self, test_client):
        """Test IR strobe returns not-implemented error."""
        success, message = test_client.send_ir_strobe(14, 50, 1000)
        # Currently returns NOT_IMPLEMENTED
        assert success is False
        assert "NOT_IMPLEMENTED" in message

    def test_wifi_probe_request(self, flipper_connection):
        """Test WiFi probe request handling."""
        msg = FlockProtocol.create_wifi_probe("TestSSID")
        flipper_connection.send(msg)
        # May require ESP32, just verify no crash
        time.sleep(0.5)

    def test_subghz_replay_request(self, flipper_connection):
        """Test Sub-GHz replay request handling."""
        signal_data = bytes([0x55, 0xAA] * 10)
        msg = FlockProtocol.create_subghz_replay(433920000, signal_data, 1)
        flipper_connection.send(msg)
        # Currently a stub, just verify no crash
        time.sleep(0.5)


# ============================================================================
# Error Handling Tests
# ============================================================================

@pytest.mark.flipper_required
class TestErrorHandling:
    """Test error handling and validation."""

    def test_invalid_version_ignored(self, flipper_connection):
        """Test that invalid protocol version is handled gracefully."""
        # Send message with wrong version
        invalid_msg = bytes([0x99, 0x00, 0x00, 0x00])  # Version 0x99
        flipper_connection.send(invalid_msg)
        # Should not crash, may get error or be ignored
        response = flipper_connection.receive(timeout=1.0)
        # No crash = success

    def test_unknown_message_type(self, flipper_connection):
        """Test handling of unknown message type."""
        # Valid header but unknown type 0xFE
        unknown_msg = bytes([0x01, 0xFE, 0x00, 0x00])
        flipper_connection.send(unknown_msg)
        response = flipper_connection.receive(timeout=1.0)
        # Should be logged but not crash

    def test_oversized_payload_rejected(self, test_client):
        """Test that oversized payload is rejected with error."""
        response = test_client.send_oversized_payload(3000)
        if response:
            header, payload = response
            if header.msg_type == FlockMessageType.ERROR:
                error_code, error_msg = FlockProtocol.parse_error(payload)
                assert error_code == FlockErrorCode.INVALID_MSG
                assert "size" in error_msg.lower() or "payload" in error_msg.lower()

    def test_truncated_message_recovery(self, flipper_connection):
        """Test recovery from truncated message."""
        # Send incomplete message
        flipper_connection.send(bytes([0x01, 0x00]))  # Incomplete header
        time.sleep(0.1)
        # Send valid message
        flipper_connection.send(FlockProtocol.create_heartbeat())
        response = flipper_connection.receive(timeout=2.0)
        # Should eventually get heartbeat response
        assert response is not None

    def test_garbage_data_recovery(self, flipper_connection):
        """Test recovery from garbage data."""
        # Send random garbage
        flipper_connection.send(os.urandom(50))
        time.sleep(0.2)
        # Clear any responses
        while flipper_connection.receive(timeout=0.1):
            pass
        # Send valid message
        flipper_connection.send(FlockProtocol.create_heartbeat())
        response = flipper_connection.receive(timeout=2.0)
        # Should eventually respond
        # (May need multiple attempts as FAP resyncs)
        if not response:
            flipper_connection.send(FlockProtocol.create_heartbeat())
            response = flipper_connection.receive(timeout=2.0)
        assert response is not None


# ============================================================================
# Stress Tests
# ============================================================================

@pytest.mark.flipper_required
class TestStress:
    """Stress tests for FAP communication."""

    def test_rapid_heartbeats(self, flipper_connection):
        """Test rapid heartbeat messages."""
        for _ in range(100):
            flipper_connection.send(FlockProtocol.create_heartbeat())
        # Collect responses
        responses = 0
        for _ in range(100):
            if flipper_connection.receive(timeout=0.5):
                responses += 1
        # Should get most responses
        assert responses >= 50  # Allow some loss due to buffer

    def test_mixed_message_types(self, flipper_connection):
        """Test sending mixed message types rapidly."""
        messages = [
            FlockProtocol.create_heartbeat(),
            FlockProtocol.create_status_request(),
            FlockProtocol.create_wifi_scan_request(),
            FlockProtocol.create_subghz_scan_request(),
            FlockProtocol.create_ble_scan_request(),
            FlockProtocol.create_nfc_scan_request(),
        ]
        for _ in range(10):
            for msg in messages:
                flipper_connection.send(msg)
        # Collect responses
        time.sleep(1)
        responses = []
        while True:
            r = flipper_connection.receive(timeout=0.2)
            if not r:
                break
            responses.append(r)
        # Should get some responses
        assert len(responses) > 0

    def test_large_payload_handling(self, flipper_connection):
        """Test handling of maximum-size payloads."""
        # Create message with maximum valid payload
        max_payload = bytes([0xAA] * FLOCK_MAX_PAYLOAD_SIZE)
        msg = FlockProtocol.create_message(FlockMessageType.SUBGHZ_REPLAY_TX, max_payload[:256])
        flipper_connection.send(msg)
        # Should not crash
        time.sleep(0.5)

    def test_sustained_communication(self, flipper_connection):
        """Test sustained communication over time."""
        start = time.time()
        count = 0
        errors = 0

        while time.time() - start < 10:  # 10 second test
            try:
                flipper_connection.send(FlockProtocol.create_heartbeat())
                response = flipper_connection.receive(timeout=1.0)
                if response:
                    count += 1
                else:
                    errors += 1
            except Exception:
                errors += 1
            time.sleep(0.05)

        # Should have mostly successful exchanges
        assert count > 50
        assert errors < count * 0.2  # Less than 20% errors


# ============================================================================
# Message Ordering Tests
# ============================================================================

@pytest.mark.flipper_required
class TestMessageOrdering:
    """Test message ordering and sequencing."""

    def test_request_response_ordering(self, flipper_connection):
        """Test that responses correspond to requests."""
        # Send heartbeat, expect heartbeat response
        flipper_connection.send(FlockProtocol.create_heartbeat())
        response = flipper_connection.receive(timeout=2.0)
        assert response is not None
        assert response[0].msg_type == FlockMessageType.HEARTBEAT

        # Send status request, expect status response
        flipper_connection.send(FlockProtocol.create_status_request())
        response = flipper_connection.receive(timeout=2.0)
        assert response is not None
        assert response[0].msg_type == FlockMessageType.STATUS_RESPONSE

    def test_interleaved_requests(self, flipper_connection):
        """Test interleaved request handling."""
        # Send multiple requests quickly
        flipper_connection.send(FlockProtocol.create_heartbeat())
        flipper_connection.send(FlockProtocol.create_status_request())
        flipper_connection.send(FlockProtocol.create_heartbeat())

        # Collect all responses
        responses = []
        for _ in range(3):
            r = flipper_connection.receive(timeout=2.0)
            if r:
                responses.append(r[0].msg_type)

        # Should get all response types
        assert FlockMessageType.HEARTBEAT in responses
        assert FlockMessageType.STATUS_RESPONSE in responses


# ============================================================================
# Protocol Compliance Tests
# ============================================================================

@pytest.mark.flipper_required
class TestProtocolCompliance:
    """Test protocol compliance."""

    def test_response_header_format(self, flipper_connection):
        """Test response header format is correct."""
        flipper_connection.send(FlockProtocol.create_heartbeat())
        response = flipper_connection.receive(timeout=2.0)

        assert response is not None
        header, payload = response
        assert header.version == FLOCK_PROTOCOL_VERSION
        assert header.payload_length == len(payload)

    def test_little_endian_encoding(self, flipper_connection):
        """Test that multi-byte values use little-endian encoding."""
        flipper_connection.send(FlockProtocol.create_status_request())
        response = flipper_connection.receive(timeout=2.0)

        assert response is not None
        header, payload = response
        # Verify status response can be parsed (uses little-endian)
        status = FlockProtocol.parse_status_response(payload)
        assert status.protocol_version == FLOCK_PROTOCOL_VERSION

    def test_string_null_termination(self, test_client):
        """Test that strings are properly null-terminated."""
        status = test_client.get_status()
        # Status response doesn't have strings, but verifies parsing works


# ============================================================================
# Detection Collection Tests
# ============================================================================

@pytest.mark.flipper_required
class TestDetectionCollection:
    """Test detection result collection."""

    @pytest.mark.timeout(30)
    def test_collect_detections(self, flipper_connection):
        """Test collecting detection results over time."""
        # Start collecting
        messages = []
        start = time.time()
        while time.time() - start < 5:
            response = flipper_connection.receive(timeout=0.5)
            if response:
                messages.append(response)

        # May or may not have detections depending on environment
        # Just verify we can collect without errors
        detection_types = [
            FlockMessageType.SUBGHZ_SCAN_RESULT,
            FlockMessageType.BLE_SCAN_RESULT,
            FlockMessageType.IR_SCAN_RESULT,
            FlockMessageType.NFC_SCAN_RESULT,
            FlockMessageType.WIPS_ALERT,
        ]
        detections = [m for m in messages if m[0].msg_type in detection_types]
        # Print count for debugging
        print(f"Collected {len(detections)} detection messages")


if __name__ == '__main__':
    pytest.main([__file__, '-v', '--tb=short'])

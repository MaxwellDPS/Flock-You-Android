"""
Unit tests for Flock Protocol encoding/decoding.

These tests verify the Python protocol implementation matches the C implementation,
without requiring a connected Flipper Zero.
"""

import pytest
import struct
from hypothesis import given, strategies as st, settings

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

from flock_protocol import (
    FlockProtocol, FlockMessageHeader, FlockMessageBuffer, FlockMessageType,
    FlockErrorCode, FlockStatusResponse, FlockWifiNetwork, FlockSubGhzDetection,
    FlockBleDevice, FlockIrDetection, FlockNfcDetection, FlockWipsAlert,
    FlockLfProbePayload, FlockIrStrobePayload, FlockWifiProbePayload,
    FlockSubGhzReplayPayload, WifiSecurityType, SubGhzModulation,
    WipsAlertType, WipsSeverity,
    FLOCK_PROTOCOL_VERSION, FLOCK_HEADER_SIZE, FLOCK_MAX_PAYLOAD_SIZE,
    FLOCK_MAX_MESSAGE_SIZE
)


class TestFlockMessageHeader:
    """Tests for message header encoding/decoding."""

    def test_header_pack_basic(self):
        """Test basic header packing."""
        header = FlockMessageHeader(
            version=FLOCK_PROTOCOL_VERSION,
            msg_type=FlockMessageType.HEARTBEAT,
            payload_length=0
        )
        packed = header.pack()
        assert len(packed) == FLOCK_HEADER_SIZE
        assert packed == bytes([0x01, 0x00, 0x00, 0x00])

    def test_header_pack_with_payload(self):
        """Test header packing with payload length."""
        header = FlockMessageHeader(
            version=FLOCK_PROTOCOL_VERSION,
            msg_type=FlockMessageType.STATUS_RESPONSE,
            payload_length=256
        )
        packed = header.pack()
        # Little-endian: 256 = 0x0100
        assert packed == bytes([0x01, 0x06, 0x00, 0x01])

    def test_header_unpack(self):
        """Test header unpacking."""
        data = bytes([0x01, 0x05, 0x10, 0x00])  # STATUS_REQUEST, length=16
        header = FlockMessageHeader.unpack(data)
        assert header.version == FLOCK_PROTOCOL_VERSION
        assert header.msg_type == FlockMessageType.STATUS_REQUEST
        assert header.payload_length == 16

    def test_header_unpack_large_payload(self):
        """Test header unpacking with large payload length."""
        data = bytes([0x01, 0x02, 0x00, 0x08])  # WIFI_SCAN_RESULT, length=2048
        header = FlockMessageHeader.unpack(data)
        assert header.payload_length == 2048

    def test_header_unpack_too_short(self):
        """Test header unpacking fails with too little data."""
        with pytest.raises(ValueError, match="Header too short"):
            FlockMessageHeader.unpack(bytes([0x01, 0x00]))

    def test_header_validate_correct_version(self):
        """Test header validation with correct version."""
        header = FlockMessageHeader(version=FLOCK_PROTOCOL_VERSION)
        assert header.validate() is True

    def test_header_validate_wrong_version(self):
        """Test header validation with wrong version."""
        header = FlockMessageHeader(version=99)
        assert header.validate() is False

    def test_header_roundtrip(self):
        """Test header pack/unpack roundtrip."""
        original = FlockMessageHeader(
            version=FLOCK_PROTOCOL_VERSION,
            msg_type=FlockMessageType.SUBGHZ_SCAN_RESULT,
            payload_length=1234
        )
        packed = original.pack()
        unpacked = FlockMessageHeader.unpack(packed)
        assert unpacked.version == original.version
        assert unpacked.msg_type == original.msg_type
        assert unpacked.payload_length == original.payload_length

    @given(st.integers(min_value=0, max_value=255),
           st.integers(min_value=0, max_value=65535))
    @settings(max_examples=100)
    def test_header_roundtrip_property(self, msg_type, payload_length):
        """Property-based test for header roundtrip."""
        original = FlockMessageHeader(
            version=FLOCK_PROTOCOL_VERSION,
            msg_type=msg_type,
            payload_length=payload_length
        )
        unpacked = FlockMessageHeader.unpack(original.pack())
        assert unpacked.msg_type == original.msg_type
        assert unpacked.payload_length == original.payload_length


class TestFlockProtocolMessages:
    """Tests for complete message creation and parsing."""

    def test_create_heartbeat(self):
        """Test heartbeat message creation."""
        msg = FlockProtocol.create_heartbeat()
        assert len(msg) == FLOCK_HEADER_SIZE
        header = FlockMessageHeader.unpack(msg)
        assert header.msg_type == FlockMessageType.HEARTBEAT
        assert header.payload_length == 0

    def test_create_status_request(self):
        """Test status request message creation."""
        msg = FlockProtocol.create_status_request()
        header = FlockMessageHeader.unpack(msg)
        assert header.msg_type == FlockMessageType.STATUS_REQUEST
        assert header.payload_length == 0

    def test_create_wifi_scan_request(self):
        """Test WiFi scan request message creation."""
        msg = FlockProtocol.create_wifi_scan_request()
        header = FlockMessageHeader.unpack(msg)
        assert header.msg_type == FlockMessageType.WIFI_SCAN_REQUEST

    def test_create_subghz_scan_request(self):
        """Test Sub-GHz scan request message creation."""
        msg = FlockProtocol.create_subghz_scan_request(300000000, 928000000)
        header, payload = FlockProtocol.parse_message(msg)
        assert header.msg_type == FlockMessageType.SUBGHZ_SCAN_REQUEST
        assert header.payload_length == 8
        freq_start, freq_end = struct.unpack('<II', payload)
        assert freq_start == 300000000
        assert freq_end == 928000000

    def test_create_error(self):
        """Test error message creation."""
        msg = FlockProtocol.create_error(FlockErrorCode.NOT_IMPLEMENTED, "Test error")
        header, payload = FlockProtocol.parse_message(msg)
        assert header.msg_type == FlockMessageType.ERROR
        error_code, error_msg = FlockProtocol.parse_error(payload)
        assert error_code == FlockErrorCode.NOT_IMPLEMENTED
        assert error_msg == "Test error"

    def test_create_error_truncates_long_message(self):
        """Test error message truncates messages over 64 chars."""
        long_msg = "x" * 100
        msg = FlockProtocol.create_error(FlockErrorCode.INVALID_MSG, long_msg)
        header, payload = FlockProtocol.parse_message(msg)
        error_code, error_msg = FlockProtocol.parse_error(payload)
        assert len(error_msg) == 64

    def test_create_lf_probe(self):
        """Test LF probe message creation."""
        msg = FlockProtocol.create_lf_probe(2000)
        header, payload = FlockProtocol.parse_message(msg)
        assert header.msg_type == FlockMessageType.LF_PROBE_TX
        probe = FlockLfProbePayload.unpack(payload)
        assert probe.duration_ms == 2000

    def test_create_ir_strobe(self):
        """Test IR strobe message creation."""
        msg = FlockProtocol.create_ir_strobe(14, 50, 1000)
        header, payload = FlockProtocol.parse_message(msg)
        assert header.msg_type == FlockMessageType.IR_STROBE_TX
        strobe = FlockIrStrobePayload.unpack(payload)
        assert strobe.frequency_hz == 14
        assert strobe.duty_cycle == 50
        assert strobe.duration_ms == 1000

    def test_create_wifi_probe(self):
        """Test WiFi probe message creation."""
        msg = FlockProtocol.create_wifi_probe("TestSSID")
        header, payload = FlockProtocol.parse_message(msg)
        assert header.msg_type == FlockMessageType.WIFI_PROBE_TX
        probe = FlockWifiProbePayload.unpack(payload)
        assert probe.ssid == "TestSSID"

    def test_create_subghz_replay(self):
        """Test Sub-GHz replay message creation."""
        signal_data = bytes([0x01, 0x02, 0x03, 0x04])
        msg = FlockProtocol.create_subghz_replay(433920000, signal_data, 5)
        header, payload = FlockProtocol.parse_message(msg)
        assert header.msg_type == FlockMessageType.SUBGHZ_REPLAY_TX
        replay = FlockSubGhzReplayPayload.unpack(payload)
        assert replay.frequency == 433920000
        assert replay.data == signal_data
        assert replay.repeat_count == 5

    def test_parse_message_too_short(self):
        """Test parse_message fails with too short data."""
        with pytest.raises(ValueError, match="Message too short"):
            FlockProtocol.parse_message(bytes([0x01, 0x00]))

    def test_parse_message_incomplete(self):
        """Test parse_message fails with incomplete message."""
        # Header says 100 bytes payload, but only 10 provided
        header = bytes([0x01, 0x00, 0x64, 0x00])  # length=100
        with pytest.raises(ValueError, match="Message incomplete"):
            FlockProtocol.parse_message(header + bytes(10))

    def test_parse_message_invalid_version(self):
        """Test parse_message fails with invalid version."""
        msg = bytes([0x99, 0x00, 0x00, 0x00])  # Invalid version
        with pytest.raises(ValueError, match="Invalid protocol version"):
            FlockProtocol.parse_message(msg)

    def test_create_message_oversized_payload(self):
        """Test create_message rejects oversized payloads."""
        oversized = bytes(FLOCK_MAX_PAYLOAD_SIZE + 1)
        with pytest.raises(ValueError, match="Payload too large"):
            FlockProtocol.create_message(FlockMessageType.HEARTBEAT, oversized)


class TestFlockStatusResponse:
    """Tests for status response encoding/decoding."""

    def test_status_response_pack(self):
        """Test status response packing."""
        status = FlockStatusResponse(
            protocol_version=1,
            wifi_board_connected=True,
            subghz_ready=True,
            ble_ready=True,
            ir_ready=True,
            nfc_ready=True,
            battery_percent=85,
            uptime_seconds=3600,
            wifi_scan_count=10,
            subghz_detection_count=25,
            ble_scan_count=5,
            ir_detection_count=3,
            nfc_detection_count=2,
            wips_alert_count=1
        )
        packed = status.pack()
        assert len(packed) == FlockStatusResponse.SIZE

    def test_status_response_unpack(self):
        """Test status response unpacking."""
        status = FlockStatusResponse(
            protocol_version=1,
            wifi_board_connected=True,
            subghz_ready=True,
            ble_ready=False,
            ir_ready=True,
            nfc_ready=True,
            battery_percent=75,
            uptime_seconds=7200,
            wifi_scan_count=20,
            subghz_detection_count=50,
            ble_scan_count=0,
            ir_detection_count=15,
            nfc_detection_count=8,
            wips_alert_count=3
        )
        packed = status.pack()
        unpacked = FlockStatusResponse.unpack(packed)

        assert unpacked.protocol_version == 1
        assert unpacked.wifi_board_connected is True
        assert unpacked.subghz_ready is True
        assert unpacked.ble_ready is False
        assert unpacked.ir_ready is True
        assert unpacked.nfc_ready is True
        assert unpacked.battery_percent == 75
        assert unpacked.uptime_seconds == 7200
        assert unpacked.wifi_scan_count == 20
        assert unpacked.subghz_detection_count == 50
        assert unpacked.ble_scan_count == 0
        assert unpacked.ir_detection_count == 15
        assert unpacked.nfc_detection_count == 8
        assert unpacked.wips_alert_count == 3


class TestFlockWifiNetwork:
    """Tests for WiFi network structure encoding/decoding."""

    def test_wifi_network_pack(self):
        """Test WiFi network packing."""
        network = FlockWifiNetwork(
            ssid="TestNetwork",
            bssid=bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
            rssi=-65,
            channel=6,
            security=WifiSecurityType.WPA2,
            hidden=False
        )
        packed = network.pack()
        assert len(packed) == FlockWifiNetwork.SIZE

    def test_wifi_network_roundtrip(self):
        """Test WiFi network pack/unpack roundtrip."""
        original = FlockWifiNetwork(
            ssid="MyHomeNetwork",
            bssid=bytes([0x11, 0x22, 0x33, 0x44, 0x55, 0x66]),
            rssi=-72,
            channel=11,
            security=WifiSecurityType.WPA3,
            hidden=True
        )
        packed = original.pack()
        unpacked = FlockWifiNetwork.unpack(packed)

        assert unpacked.ssid == original.ssid
        assert unpacked.bssid == original.bssid
        assert unpacked.rssi == original.rssi
        assert unpacked.channel == original.channel
        assert unpacked.security == original.security
        assert unpacked.hidden == original.hidden

    def test_wifi_network_ssid_truncation(self):
        """Test WiFi network SSID truncation for long names."""
        long_ssid = "A" * 50  # Longer than 32 chars
        network = FlockWifiNetwork(ssid=long_ssid)
        packed = network.pack()
        unpacked = FlockWifiNetwork.unpack(packed)
        assert len(unpacked.ssid) <= 32


class TestFlockSubGhzDetection:
    """Tests for Sub-GHz detection structure encoding/decoding."""

    def test_subghz_detection_pack(self):
        """Test Sub-GHz detection packing."""
        detection = FlockSubGhzDetection(
            frequency=433920000,
            rssi=-45,
            modulation=SubGhzModulation.OOK,
            duration_ms=150,
            bandwidth=500000,
            protocol_id=1,
            protocol_name="Princeton"
        )
        packed = detection.pack()
        assert len(packed) == FlockSubGhzDetection.SIZE

    def test_subghz_detection_roundtrip(self):
        """Test Sub-GHz detection pack/unpack roundtrip."""
        original = FlockSubGhzDetection(
            frequency=315000000,
            rssi=-60,
            modulation=SubGhzModulation.FSK,
            duration_ms=200,
            bandwidth=250000,
            protocol_id=5,
            protocol_name="Came"
        )
        packed = original.pack()
        unpacked = FlockSubGhzDetection.unpack(packed)

        assert unpacked.frequency == original.frequency
        assert unpacked.rssi == original.rssi
        assert unpacked.modulation == original.modulation
        assert unpacked.duration_ms == original.duration_ms
        assert unpacked.bandwidth == original.bandwidth
        assert unpacked.protocol_id == original.protocol_id
        assert unpacked.protocol_name == original.protocol_name


class TestFlockNfcDetection:
    """Tests for NFC detection structure encoding/decoding."""

    def test_nfc_detection_pack(self):
        """Test NFC detection packing."""
        detection = FlockNfcDetection(
            uid=bytes([0x04, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0x00, 0x00, 0x00]),
            uid_len=7,
            nfc_type=1,  # Type A
            sak=0x08,
            atqa=bytes([0x04, 0x00]),
            type_name="MIFARE Classic"
        )
        packed = detection.pack()
        assert len(packed) == FlockNfcDetection.SIZE

    def test_nfc_detection_roundtrip(self):
        """Test NFC detection pack/unpack roundtrip."""
        original = FlockNfcDetection(
            uid=bytes([0x04, 0x11, 0x22, 0x33, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]),
            uid_len=4,
            nfc_type=1,
            sak=0x00,
            atqa=bytes([0x44, 0x00]),
            type_name="Ultralight"
        )
        packed = original.pack()
        unpacked = FlockNfcDetection.unpack(packed)

        assert unpacked.uid[:original.uid_len] == original.uid[:original.uid_len]
        assert unpacked.uid_len == original.uid_len
        assert unpacked.nfc_type == original.nfc_type
        assert unpacked.sak == original.sak
        assert unpacked.atqa == original.atqa
        assert unpacked.type_name == original.type_name


class TestFlockMessageBuffer:
    """Tests for message buffer stream processing."""

    def test_buffer_single_message(self):
        """Test buffer with single complete message."""
        buffer = FlockMessageBuffer()
        msg = FlockProtocol.create_heartbeat()
        buffer.append(msg)
        messages = buffer.get_messages()
        assert len(messages) == 1
        assert messages[0][0].msg_type == FlockMessageType.HEARTBEAT

    def test_buffer_multiple_messages(self):
        """Test buffer with multiple complete messages."""
        buffer = FlockMessageBuffer()
        buffer.append(FlockProtocol.create_heartbeat())
        buffer.append(FlockProtocol.create_status_request())
        buffer.append(FlockProtocol.create_heartbeat())
        messages = buffer.get_messages()
        assert len(messages) == 3

    def test_buffer_partial_message(self):
        """Test buffer with partial message waits for more data."""
        buffer = FlockMessageBuffer()
        msg = FlockProtocol.create_subghz_scan_request()
        # Only send first half
        buffer.append(msg[:len(msg)//2])
        messages = buffer.get_messages()
        assert len(messages) == 0
        # Send rest
        buffer.append(msg[len(msg)//2:])
        messages = buffer.get_messages()
        assert len(messages) == 1

    def test_buffer_fragmented_receive(self):
        """Test buffer handles fragmented byte-by-byte receive."""
        buffer = FlockMessageBuffer()
        msg = FlockProtocol.create_heartbeat()
        for byte in msg:
            buffer.append(bytes([byte]))
        messages = buffer.get_messages()
        assert len(messages) == 1

    def test_buffer_invalid_version_recovery(self):
        """Test buffer recovers from invalid version byte."""
        buffer = FlockMessageBuffer()
        # Invalid data followed by valid message
        buffer.append(bytes([0x99, 0x99, 0x99]))
        buffer.append(FlockProtocol.create_heartbeat())
        messages = buffer.get_messages()
        assert len(messages) == 1
        assert messages[0][0].msg_type == FlockMessageType.HEARTBEAT

    def test_buffer_oversized_payload_recovery(self):
        """Test buffer recovers from oversized payload length."""
        buffer = FlockMessageBuffer()
        # Invalid header with oversized payload
        buffer.append(bytes([0x01, 0x00, 0xFF, 0xFF]))  # payload_length=65535
        buffer.append(FlockProtocol.create_heartbeat())
        messages = buffer.get_messages()
        # Should recover and find the valid heartbeat
        assert len(messages) >= 1

    def test_buffer_clear(self):
        """Test buffer clear."""
        buffer = FlockMessageBuffer()
        buffer.append(FlockProtocol.create_heartbeat())
        buffer.clear()
        messages = buffer.get_messages()
        assert len(messages) == 0

    def test_buffer_overflow_protection(self):
        """Test buffer doesn't grow unbounded."""
        buffer = FlockMessageBuffer(max_size=1000)
        # Add more data than max_size
        for _ in range(20):
            buffer.append(bytes(100))
        # Buffer should be truncated
        assert len(buffer.buffer) <= 1000


class TestFlockWipsAlert:
    """Tests for WIPS alert structure encoding/decoding."""

    def test_wips_alert_pack(self):
        """Test WIPS alert packing."""
        alert = FlockWipsAlert(
            timestamp=1234567890,
            alert_type=WipsAlertType.DEAUTH_ATTACK,
            severity=WipsSeverity.HIGH,
            ssid="TargetNetwork",
            bssid_count=2,
            bssids=[
                bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
                bytes([0x11, 0x22, 0x33, 0x44, 0x55, 0x66])
            ],
            description="Deauthentication attack detected"
        )
        packed = alert.pack()
        assert len(packed) == FlockWipsAlert.SIZE

    def test_wips_alert_roundtrip(self):
        """Test WIPS alert pack/unpack roundtrip."""
        original = FlockWipsAlert(
            timestamp=9999999,
            alert_type=WipsAlertType.EVIL_TWIN,
            severity=WipsSeverity.CRITICAL,
            ssid="FakeNetwork",
            bssid_count=1,
            bssids=[bytes([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01])],
            description="Evil twin AP detected"
        )
        packed = original.pack()
        unpacked = FlockWipsAlert.unpack(packed)

        assert unpacked.timestamp == original.timestamp
        assert unpacked.alert_type == original.alert_type
        assert unpacked.severity == original.severity
        assert unpacked.ssid == original.ssid
        # Description may be truncated
        assert original.description in unpacked.description or unpacked.description in original.description


class TestParseResults:
    """Tests for result parsing functions."""

    def test_parse_wifi_result(self):
        """Test WiFi result parsing."""
        # Create a mock WiFi result payload
        timestamp = 12345
        networks = [
            FlockWifiNetwork(ssid="Net1", rssi=-50, channel=1),
            FlockWifiNetwork(ssid="Net2", rssi=-70, channel=6),
        ]
        payload = struct.pack('<IB', timestamp, len(networks))
        for net in networks:
            payload += net.pack()

        ts, parsed_networks = FlockProtocol.parse_wifi_result(payload)
        assert ts == timestamp
        assert len(parsed_networks) == 2
        assert parsed_networks[0].ssid == "Net1"
        assert parsed_networks[1].ssid == "Net2"

    def test_parse_subghz_result(self):
        """Test Sub-GHz result parsing."""
        timestamp = 54321
        freq_start = 300000000
        freq_end = 500000000
        detections = [
            FlockSubGhzDetection(frequency=433920000, rssi=-40, protocol_name="Test")
        ]
        payload = struct.pack('<IIIB', timestamp, freq_start, freq_end, len(detections))
        for det in detections:
            payload += det.pack()

        ts, fs, fe, parsed = FlockProtocol.parse_subghz_result(payload)
        assert ts == timestamp
        assert fs == freq_start
        assert fe == freq_end
        assert len(parsed) == 1
        assert parsed[0].frequency == 433920000

    def test_parse_nfc_result(self):
        """Test NFC result parsing."""
        timestamp = 99999
        detections = [
            FlockNfcDetection(uid_len=4, sak=0x08, type_name="Classic")
        ]
        payload = struct.pack('<IB', timestamp, len(detections))
        for det in detections:
            payload += det.pack()

        ts, parsed = FlockProtocol.parse_nfc_result(payload)
        assert ts == timestamp
        assert len(parsed) == 1
        assert parsed[0].sak == 0x08


if __name__ == '__main__':
    pytest.main([__file__, '-v'])

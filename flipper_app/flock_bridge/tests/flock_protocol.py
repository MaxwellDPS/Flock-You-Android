"""
Flock Protocol - Python implementation matching the C protocol in flock_protocol.h/c

This module provides encoding/decoding for Flock Bridge protocol messages,
used for testing the FAP communication layer.
"""

import struct
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Optional, List, Tuple, Union
import logging

logger = logging.getLogger(__name__)

# ============================================================================
# Protocol Constants
# ============================================================================

FLOCK_PROTOCOL_VERSION = 1
FLOCK_HEADER_SIZE = 4
FLOCK_MAX_PAYLOAD_SIZE = 2048
FLOCK_MAX_MESSAGE_SIZE = FLOCK_HEADER_SIZE + FLOCK_MAX_PAYLOAD_SIZE

# ============================================================================
# Message Types
# ============================================================================

class FlockMessageType(IntEnum):
    HEARTBEAT = 0x00
    WIFI_SCAN_REQUEST = 0x01
    WIFI_SCAN_RESULT = 0x02
    SUBGHZ_SCAN_REQUEST = 0x03
    SUBGHZ_SCAN_RESULT = 0x04
    STATUS_REQUEST = 0x05
    STATUS_RESPONSE = 0x06
    WIPS_ALERT = 0x07
    BLE_SCAN_REQUEST = 0x08
    BLE_SCAN_RESULT = 0x09
    IR_SCAN_REQUEST = 0x0A
    IR_SCAN_RESULT = 0x0B
    NFC_SCAN_REQUEST = 0x0C
    NFC_SCAN_RESULT = 0x0D

    # Active Probe TX Commands - Public Safety & Fleet
    LF_PROBE_TX = 0x0E
    IR_STROBE_TX = 0x0F
    WIFI_PROBE_TX = 0x10
    BLE_ACTIVE_SCAN = 0x11

    # Active Probe TX Commands - Infrastructure
    ZIGBEE_BEACON_TX = 0x12
    GPIO_PULSE_TX = 0x13

    # Active Probe TX Commands - Physical Access
    SUBGHZ_REPLAY_TX = 0x14
    WIEGAND_REPLAY_TX = 0x15
    MAGSPOOF_TX = 0x16
    IBUTTON_EMULATE = 0x17

    # Active Probe TX Commands - Digital
    NRF24_INJECT_TX = 0x18

    # Passive Scan Configuration
    SUBGHZ_CONFIG = 0x20
    IR_CONFIG = 0x21
    NRF24_CONFIG = 0x22

    ERROR = 0xFF


class FlockErrorCode(IntEnum):
    NONE = 0x00
    INVALID_MSG = 0x01
    NOT_IMPLEMENTED = 0x02
    HARDWARE_FAIL = 0x03
    BUSY = 0x04
    TIMEOUT = 0x05
    INVALID_PARAM = 0x06


class WifiSecurityType(IntEnum):
    OPEN = 0
    WEP = 1
    WPA = 2
    WPA2 = 3
    WPA3 = 4
    WPA2_ENTERPRISE = 5
    WPA3_ENTERPRISE = 6
    UNKNOWN = 255


class SubGhzModulation(IntEnum):
    AM = 0
    FM = 1
    ASK = 2
    FSK = 3
    PSK = 4
    OOK = 5
    GFSK = 6
    UNKNOWN = 255


class WipsAlertType(IntEnum):
    EVIL_TWIN = 0
    DEAUTH_ATTACK = 1
    KARMA_ATTACK = 2
    HIDDEN_NETWORK_STRONG = 3
    SUSPICIOUS_OPEN_NETWORK = 4
    WEAK_ENCRYPTION = 5
    CHANNEL_INTERFERENCE = 6
    MAC_SPOOFING = 7
    ROGUE_AP = 8
    SIGNAL_ANOMALY = 9
    BEACON_FLOOD = 10


class WipsSeverity(IntEnum):
    CRITICAL = 0
    HIGH = 1
    MEDIUM = 2
    LOW = 3
    INFO = 4


# ============================================================================
# Data Structures
# ============================================================================

@dataclass
class FlockMessageHeader:
    version: int = FLOCK_PROTOCOL_VERSION
    msg_type: int = 0
    payload_length: int = 0

    def pack(self) -> bytes:
        return struct.pack('<BBH', self.version, self.msg_type, self.payload_length)

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockMessageHeader':
        if len(data) < FLOCK_HEADER_SIZE:
            raise ValueError(f"Header too short: {len(data)} < {FLOCK_HEADER_SIZE}")
        version, msg_type, payload_length = struct.unpack('<BBH', data[:FLOCK_HEADER_SIZE])
        return cls(version=version, msg_type=msg_type, payload_length=payload_length)

    def validate(self) -> bool:
        return self.version == FLOCK_PROTOCOL_VERSION


@dataclass
class FlockWifiNetwork:
    ssid: str = ""
    bssid: bytes = field(default_factory=lambda: bytes(6))
    rssi: int = 0
    channel: int = 0
    security: WifiSecurityType = WifiSecurityType.UNKNOWN
    hidden: bool = False

    SIZE = 43  # 33 + 6 + 1 + 1 + 1 + 1

    def pack(self) -> bytes:
        ssid_bytes = self.ssid.encode('utf-8')[:32].ljust(33, b'\x00')
        return (
            ssid_bytes +
            self.bssid[:6].ljust(6, b'\x00') +
            struct.pack('<bBBB', self.rssi, self.channel, self.security, 1 if self.hidden else 0)
        )

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockWifiNetwork':
        if len(data) < cls.SIZE:
            raise ValueError(f"Network data too short: {len(data)} < {cls.SIZE}")
        ssid = data[:33].rstrip(b'\x00').decode('utf-8', errors='replace')
        bssid = data[33:39]
        rssi, channel, security, hidden = struct.unpack('<bBBB', data[39:43])
        return cls(ssid=ssid, bssid=bssid, rssi=rssi, channel=channel,
                   security=WifiSecurityType(security), hidden=bool(hidden))


@dataclass
class FlockSubGhzDetection:
    frequency: int = 0
    rssi: int = 0
    modulation: SubGhzModulation = SubGhzModulation.UNKNOWN
    duration_ms: int = 0
    bandwidth: int = 0
    protocol_id: int = 0
    protocol_name: str = ""

    SIZE = 29  # 4 + 1 + 1 + 2 + 4 + 1 + 16

    def pack(self) -> bytes:
        name_bytes = self.protocol_name.encode('utf-8')[:15].ljust(16, b'\x00')
        return struct.pack(
            '<IbBHIB',
            self.frequency, self.rssi, self.modulation,
            self.duration_ms, self.bandwidth, self.protocol_id
        ) + name_bytes

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockSubGhzDetection':
        if len(data) < cls.SIZE:
            raise ValueError(f"SubGHz data too short: {len(data)} < {cls.SIZE}")
        frequency, rssi, modulation, duration_ms, bandwidth, protocol_id = struct.unpack(
            '<IbBHIB', data[:13]
        )
        protocol_name = data[13:29].rstrip(b'\x00').decode('utf-8', errors='replace')
        return cls(frequency=frequency, rssi=rssi, modulation=SubGhzModulation(modulation),
                   duration_ms=duration_ms, bandwidth=bandwidth, protocol_id=protocol_id,
                   protocol_name=protocol_name)


@dataclass
class FlockBleDevice:
    mac_address: bytes = field(default_factory=lambda: bytes(6))
    name: str = ""
    rssi: int = 0
    address_type: int = 0
    is_connectable: bool = False
    service_uuid_count: int = 0
    service_uuids: List[bytes] = field(default_factory=list)
    manufacturer_id: bytes = field(default_factory=lambda: bytes(2))
    manufacturer_data_len: int = 0
    manufacturer_data: bytes = field(default_factory=lambda: bytes(32))

    SIZE = 6 + 32 + 1 + 1 + 1 + 1 + 64 + 2 + 1 + 32  # 141 bytes

    def pack(self) -> bytes:
        name_bytes = self.name.encode('utf-8')[:31].ljust(32, b'\x00')
        uuid_bytes = b''.join(uuid[:16].ljust(16, b'\x00') for uuid in (self.service_uuids + [bytes(16)] * 4)[:4])
        mfr_data = self.manufacturer_data[:32].ljust(32, b'\x00')
        return (
            self.mac_address[:6].ljust(6, b'\x00') +
            name_bytes +
            struct.pack('<bBBB', self.rssi, self.address_type,
                       1 if self.is_connectable else 0, min(self.service_uuid_count, 4)) +
            uuid_bytes +
            self.manufacturer_id[:2].ljust(2, b'\x00') +
            struct.pack('<B', min(self.manufacturer_data_len, 32)) +
            mfr_data
        )

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockBleDevice':
        if len(data) < cls.SIZE:
            raise ValueError(f"BLE device data too short: {len(data)} < {cls.SIZE}")
        mac = data[:6]
        name = data[6:38].rstrip(b'\x00').decode('utf-8', errors='replace')
        rssi, addr_type, connectable, uuid_count = struct.unpack('<bBBB', data[38:42])
        uuids = [data[42 + i*16:42 + (i+1)*16] for i in range(min(uuid_count, 4))]
        mfr_id = data[106:108]
        mfr_len = data[108]
        mfr_data = data[109:141]
        return cls(mac_address=mac, name=name, rssi=rssi, address_type=addr_type,
                   is_connectable=bool(connectable), service_uuid_count=uuid_count,
                   service_uuids=uuids, manufacturer_id=mfr_id,
                   manufacturer_data_len=mfr_len, manufacturer_data=mfr_data)


@dataclass
class FlockIrDetection:
    timestamp: int = 0
    protocol_id: int = 0
    protocol_name: str = ""
    address: int = 0
    command: int = 0
    repeat: bool = False
    signal_strength: int = 0

    SIZE = 4 + 1 + 16 + 4 + 4 + 1 + 1  # 31 bytes

    def pack(self) -> bytes:
        name_bytes = self.protocol_name.encode('utf-8')[:15].ljust(16, b'\x00')
        return struct.pack('<IB', self.timestamp, self.protocol_id) + name_bytes + \
               struct.pack('<IIBb', self.address, self.command, 1 if self.repeat else 0, self.signal_strength)

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockIrDetection':
        if len(data) < cls.SIZE:
            raise ValueError(f"IR detection data too short: {len(data)} < {cls.SIZE}")
        timestamp, protocol_id = struct.unpack('<IB', data[:5])
        protocol_name = data[5:21].rstrip(b'\x00').decode('utf-8', errors='replace')
        address, command, repeat, signal = struct.unpack('<IIBb', data[21:31])
        return cls(timestamp=timestamp, protocol_id=protocol_id, protocol_name=protocol_name,
                   address=address, command=command, repeat=bool(repeat), signal_strength=signal)


@dataclass
class FlockNfcDetection:
    uid: bytes = field(default_factory=lambda: bytes(10))
    uid_len: int = 0
    nfc_type: int = 0
    sak: int = 0
    atqa: bytes = field(default_factory=lambda: bytes(2))
    type_name: str = ""

    SIZE = 10 + 1 + 1 + 1 + 2 + 16  # 31 bytes

    def pack(self) -> bytes:
        uid_bytes = self.uid[:10].ljust(10, b'\x00')
        name_bytes = self.type_name.encode('utf-8')[:15].ljust(16, b'\x00')
        return uid_bytes + struct.pack('<BBB', self.uid_len, self.nfc_type, self.sak) + \
               self.atqa[:2].ljust(2, b'\x00') + name_bytes

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockNfcDetection':
        if len(data) < cls.SIZE:
            raise ValueError(f"NFC detection data too short: {len(data)} < {cls.SIZE}")
        uid = data[:10]
        uid_len, nfc_type, sak = struct.unpack('<BBB', data[10:13])
        atqa = data[13:15]
        type_name = data[15:31].rstrip(b'\x00').decode('utf-8', errors='replace')
        return cls(uid=uid, uid_len=uid_len, nfc_type=nfc_type, sak=sak,
                   atqa=atqa, type_name=type_name)


@dataclass
class FlockStatusResponse:
    protocol_version: int = FLOCK_PROTOCOL_VERSION
    wifi_board_connected: bool = False
    subghz_ready: bool = False
    ble_ready: bool = False
    ir_ready: bool = False
    nfc_ready: bool = False
    battery_percent: int = 0
    uptime_seconds: int = 0
    wifi_scan_count: int = 0
    subghz_detection_count: int = 0
    ble_scan_count: int = 0
    ir_detection_count: int = 0
    nfc_detection_count: int = 0
    wips_alert_count: int = 0

    SIZE = 1 + 1 + 1 + 1 + 1 + 1 + 1 + 4 + 2*6  # 19 bytes

    def pack(self) -> bytes:
        return struct.pack(
            '<BBBBBBBI6H',
            self.protocol_version,
            1 if self.wifi_board_connected else 0,
            1 if self.subghz_ready else 0,
            1 if self.ble_ready else 0,
            1 if self.ir_ready else 0,
            1 if self.nfc_ready else 0,
            self.battery_percent,
            self.uptime_seconds,
            self.wifi_scan_count,
            self.subghz_detection_count,
            self.ble_scan_count,
            self.ir_detection_count,
            self.nfc_detection_count,
            self.wips_alert_count
        )

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockStatusResponse':
        if len(data) < cls.SIZE:
            raise ValueError(f"Status response too short: {len(data)} < {cls.SIZE}")
        values = struct.unpack('<BBBBBBBI6H', data[:cls.SIZE])
        return cls(
            protocol_version=values[0],
            wifi_board_connected=bool(values[1]),
            subghz_ready=bool(values[2]),
            ble_ready=bool(values[3]),
            ir_ready=bool(values[4]),
            nfc_ready=bool(values[5]),
            battery_percent=values[6],
            uptime_seconds=values[7],
            wifi_scan_count=values[8],
            subghz_detection_count=values[9],
            ble_scan_count=values[10],
            ir_detection_count=values[11],
            nfc_detection_count=values[12],
            wips_alert_count=values[13]
        )


@dataclass
class FlockWipsAlert:
    timestamp: int = 0
    alert_type: WipsAlertType = WipsAlertType.EVIL_TWIN
    severity: WipsSeverity = WipsSeverity.INFO
    ssid: str = ""
    bssid_count: int = 0
    bssids: List[bytes] = field(default_factory=list)
    description: str = ""

    SIZE = 4 + 1 + 1 + 33 + 1 + 24 + 64  # 128 bytes

    def pack(self) -> bytes:
        ssid_bytes = self.ssid.encode('utf-8')[:32].ljust(33, b'\x00')
        bssid_bytes = b''.join((b[:6].ljust(6, b'\x00') for b in (self.bssids + [bytes(6)] * 4)[:4]))
        desc_bytes = self.description.encode('utf-8')[:63].ljust(64, b'\x00')
        return struct.pack('<IBB', self.timestamp, self.alert_type, self.severity) + \
               ssid_bytes + struct.pack('<B', min(self.bssid_count, 4)) + bssid_bytes + desc_bytes

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockWipsAlert':
        if len(data) < cls.SIZE:
            raise ValueError(f"WIPS alert too short: {len(data)} < {cls.SIZE}")
        timestamp, alert_type, severity = struct.unpack('<IBB', data[:6])
        ssid = data[6:39].rstrip(b'\x00').decode('utf-8', errors='replace')
        bssid_count = data[39]
        bssids = [data[40 + i*6:40 + (i+1)*6] for i in range(min(bssid_count, 4))]
        description = data[64:128].rstrip(b'\x00').decode('utf-8', errors='replace')
        return cls(timestamp=timestamp, alert_type=WipsAlertType(alert_type),
                   severity=WipsSeverity(severity), ssid=ssid, bssid_count=bssid_count,
                   bssids=bssids, description=description)


# ============================================================================
# Active Probe Payloads
# ============================================================================

@dataclass
class FlockLfProbePayload:
    duration_ms: int = 1000

    def pack(self) -> bytes:
        return struct.pack('<H', min(max(self.duration_ms, 100), 5000))

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockLfProbePayload':
        duration_ms = struct.unpack('<H', data[:2])[0]
        return cls(duration_ms=duration_ms)


@dataclass
class FlockIrStrobePayload:
    frequency_hz: int = 14
    duty_cycle: int = 50
    duration_ms: int = 1000

    def pack(self) -> bytes:
        return struct.pack('<HBH', self.frequency_hz, min(self.duty_cycle, 100),
                          min(max(self.duration_ms, 100), 10000))

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockIrStrobePayload':
        freq, duty, dur = struct.unpack('<HBH', data[:5])
        return cls(frequency_hz=freq, duty_cycle=duty, duration_ms=dur)


@dataclass
class FlockWifiProbePayload:
    ssid: str = ""

    def pack(self) -> bytes:
        ssid_bytes = self.ssid.encode('utf-8')[:32]
        return struct.pack('<B', len(ssid_bytes)) + ssid_bytes

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockWifiProbePayload':
        ssid_len = data[0]
        ssid = data[1:1+ssid_len].decode('utf-8', errors='replace')
        return cls(ssid=ssid)


@dataclass
class FlockSubGhzReplayPayload:
    frequency: int = 433920000
    data: bytes = field(default_factory=bytes)
    repeat_count: int = 1

    MAX_DATA_SIZE = 256

    def pack(self) -> bytes:
        data_bytes = self.data[:self.MAX_DATA_SIZE]
        return struct.pack('<IHB', self.frequency, len(data_bytes),
                          min(self.repeat_count, 100)) + data_bytes

    @classmethod
    def unpack(cls, data: bytes) -> 'FlockSubGhzReplayPayload':
        frequency, data_len, repeat_count = struct.unpack('<IHB', data[:7])
        signal_data = data[7:7+data_len]
        return cls(frequency=frequency, data=signal_data, repeat_count=repeat_count)


# ============================================================================
# Protocol Encoder/Decoder
# ============================================================================

class FlockProtocol:
    """Encoder/decoder for Flock Bridge protocol messages."""

    @staticmethod
    def create_message(msg_type: FlockMessageType, payload: bytes = b'') -> bytes:
        """Create a complete message with header and payload."""
        if len(payload) > FLOCK_MAX_PAYLOAD_SIZE:
            raise ValueError(f"Payload too large: {len(payload)} > {FLOCK_MAX_PAYLOAD_SIZE}")
        header = FlockMessageHeader(
            version=FLOCK_PROTOCOL_VERSION,
            msg_type=msg_type,
            payload_length=len(payload)
        )
        return header.pack() + payload

    @staticmethod
    def create_heartbeat() -> bytes:
        """Create a heartbeat request message."""
        return FlockProtocol.create_message(FlockMessageType.HEARTBEAT)

    @staticmethod
    def create_status_request() -> bytes:
        """Create a status request message."""
        return FlockProtocol.create_message(FlockMessageType.STATUS_REQUEST)

    @staticmethod
    def create_wifi_scan_request() -> bytes:
        """Create a WiFi scan request message."""
        return FlockProtocol.create_message(FlockMessageType.WIFI_SCAN_REQUEST)

    @staticmethod
    def create_subghz_scan_request(frequency_start: int = 300000000,
                                   frequency_end: int = 928000000) -> bytes:
        """Create a Sub-GHz scan request message."""
        payload = struct.pack('<II', frequency_start, frequency_end)
        return FlockProtocol.create_message(FlockMessageType.SUBGHZ_SCAN_REQUEST, payload)

    @staticmethod
    def create_ble_scan_request() -> bytes:
        """Create a BLE scan request message."""
        return FlockProtocol.create_message(FlockMessageType.BLE_SCAN_REQUEST)

    @staticmethod
    def create_ir_scan_request() -> bytes:
        """Create an IR scan request message."""
        return FlockProtocol.create_message(FlockMessageType.IR_SCAN_REQUEST)

    @staticmethod
    def create_nfc_scan_request() -> bytes:
        """Create an NFC scan request message."""
        return FlockProtocol.create_message(FlockMessageType.NFC_SCAN_REQUEST)

    @staticmethod
    def create_lf_probe(duration_ms: int = 1000) -> bytes:
        """Create an LF probe TX message."""
        payload = FlockLfProbePayload(duration_ms=duration_ms)
        return FlockProtocol.create_message(FlockMessageType.LF_PROBE_TX, payload.pack())

    @staticmethod
    def create_ir_strobe(frequency_hz: int = 14, duty_cycle: int = 50,
                         duration_ms: int = 1000) -> bytes:
        """Create an IR strobe TX message."""
        payload = FlockIrStrobePayload(frequency_hz=frequency_hz, duty_cycle=duty_cycle,
                                       duration_ms=duration_ms)
        return FlockProtocol.create_message(FlockMessageType.IR_STROBE_TX, payload.pack())

    @staticmethod
    def create_wifi_probe(ssid: str) -> bytes:
        """Create a WiFi probe TX message."""
        payload = FlockWifiProbePayload(ssid=ssid)
        return FlockProtocol.create_message(FlockMessageType.WIFI_PROBE_TX, payload.pack())

    @staticmethod
    def create_subghz_replay(frequency: int, data: bytes, repeat_count: int = 1) -> bytes:
        """Create a Sub-GHz replay TX message."""
        payload = FlockSubGhzReplayPayload(frequency=frequency, data=data,
                                           repeat_count=repeat_count)
        return FlockProtocol.create_message(FlockMessageType.SUBGHZ_REPLAY_TX, payload.pack())

    @staticmethod
    def create_error(error_code: FlockErrorCode, message: str = "") -> bytes:
        """Create an error response message."""
        msg_bytes = message.encode('utf-8')[:64]
        payload = struct.pack('<B', error_code) + msg_bytes
        return FlockProtocol.create_message(FlockMessageType.ERROR, payload)

    @staticmethod
    def parse_message(data: bytes) -> Tuple[FlockMessageHeader, bytes]:
        """Parse a message into header and payload."""
        if len(data) < FLOCK_HEADER_SIZE:
            raise ValueError(f"Message too short: {len(data)} < {FLOCK_HEADER_SIZE}")

        header = FlockMessageHeader.unpack(data)
        if not header.validate():
            raise ValueError(f"Invalid protocol version: {header.version}")

        expected_size = FLOCK_HEADER_SIZE + header.payload_length
        if len(data) < expected_size:
            raise ValueError(f"Message incomplete: {len(data)} < {expected_size}")

        payload = data[FLOCK_HEADER_SIZE:expected_size]
        return header, payload

    @staticmethod
    def parse_status_response(payload: bytes) -> FlockStatusResponse:
        """Parse a status response payload."""
        return FlockStatusResponse.unpack(payload)

    @staticmethod
    def parse_error(payload: bytes) -> Tuple[FlockErrorCode, str]:
        """Parse an error response payload."""
        if len(payload) < 1:
            raise ValueError("Error payload too short")
        error_code = FlockErrorCode(payload[0])
        message = payload[1:].decode('utf-8', errors='replace') if len(payload) > 1 else ""
        return error_code, message

    @staticmethod
    def parse_wifi_result(payload: bytes) -> Tuple[int, List[FlockWifiNetwork]]:
        """Parse a WiFi scan result payload."""
        if len(payload) < 5:
            raise ValueError("WiFi result payload too short")
        timestamp = struct.unpack('<I', payload[:4])[0]
        count = payload[4]
        networks = []
        offset = 5
        for _ in range(count):
            if offset + FlockWifiNetwork.SIZE > len(payload):
                break
            network = FlockWifiNetwork.unpack(payload[offset:offset + FlockWifiNetwork.SIZE])
            networks.append(network)
            offset += FlockWifiNetwork.SIZE
        return timestamp, networks

    @staticmethod
    def parse_subghz_result(payload: bytes) -> Tuple[int, int, int, List[FlockSubGhzDetection]]:
        """Parse a Sub-GHz scan result payload."""
        if len(payload) < 13:
            raise ValueError("SubGHz result payload too short")
        timestamp, freq_start, freq_end = struct.unpack('<III', payload[:12])
        count = payload[12]
        detections = []
        offset = 13
        for _ in range(count):
            if offset + FlockSubGhzDetection.SIZE > len(payload):
                break
            detection = FlockSubGhzDetection.unpack(payload[offset:offset + FlockSubGhzDetection.SIZE])
            detections.append(detection)
            offset += FlockSubGhzDetection.SIZE
        return timestamp, freq_start, freq_end, detections

    @staticmethod
    def parse_ble_result(payload: bytes) -> Tuple[int, List[FlockBleDevice]]:
        """Parse a BLE scan result payload."""
        if len(payload) < 5:
            raise ValueError("BLE result payload too short")
        timestamp = struct.unpack('<I', payload[:4])[0]
        count = payload[4]
        devices = []
        offset = 5
        for _ in range(count):
            if offset + FlockBleDevice.SIZE > len(payload):
                break
            device = FlockBleDevice.unpack(payload[offset:offset + FlockBleDevice.SIZE])
            devices.append(device)
            offset += FlockBleDevice.SIZE
        return timestamp, devices

    @staticmethod
    def parse_ir_result(payload: bytes) -> Tuple[int, List[FlockIrDetection]]:
        """Parse an IR scan result payload."""
        if len(payload) < 5:
            raise ValueError("IR result payload too short")
        timestamp = struct.unpack('<I', payload[:4])[0]
        count = payload[4]
        detections = []
        offset = 5
        for _ in range(count):
            if offset + FlockIrDetection.SIZE > len(payload):
                break
            detection = FlockIrDetection.unpack(payload[offset:offset + FlockIrDetection.SIZE])
            detections.append(detection)
            offset += FlockIrDetection.SIZE
        return timestamp, detections

    @staticmethod
    def parse_nfc_result(payload: bytes) -> Tuple[int, List[FlockNfcDetection]]:
        """Parse an NFC scan result payload."""
        if len(payload) < 5:
            raise ValueError("NFC result payload too short")
        timestamp = struct.unpack('<I', payload[:4])[0]
        count = payload[4]
        detections = []
        offset = 5
        for _ in range(count):
            if offset + FlockNfcDetection.SIZE > len(payload):
                break
            detection = FlockNfcDetection.unpack(payload[offset:offset + FlockNfcDetection.SIZE])
            detections.append(detection)
            offset += FlockNfcDetection.SIZE
        return timestamp, detections


# ============================================================================
# Message Buffer for Stream Processing
# ============================================================================

class FlockMessageBuffer:
    """Buffer for accumulating and parsing streamed messages."""

    def __init__(self, max_size: int = FLOCK_MAX_MESSAGE_SIZE * 4):
        self.buffer = bytearray()
        self.max_size = max_size

    def append(self, data: bytes) -> None:
        """Append data to the buffer."""
        self.buffer.extend(data)
        # Prevent unbounded growth
        if len(self.buffer) > self.max_size:
            # Keep only the last max_size bytes
            self.buffer = self.buffer[-self.max_size:]
            logger.warning(f"Message buffer overflow, truncated to {self.max_size} bytes")

    def get_messages(self) -> List[Tuple[FlockMessageHeader, bytes]]:
        """Extract all complete messages from the buffer."""
        messages = []

        while len(self.buffer) >= FLOCK_HEADER_SIZE:
            try:
                header = FlockMessageHeader.unpack(bytes(self.buffer))
            except ValueError:
                # Invalid header, skip one byte and try again
                self.buffer = self.buffer[1:]
                continue

            if not header.validate():
                # Invalid version, skip one byte
                self.buffer = self.buffer[1:]
                continue

            # Check if payload is valid
            if header.payload_length > FLOCK_MAX_PAYLOAD_SIZE:
                # Invalid payload length, skip header
                self.buffer = self.buffer[1:]
                continue

            msg_size = FLOCK_HEADER_SIZE + header.payload_length
            if len(self.buffer) < msg_size:
                # Incomplete message, wait for more data
                break

            # Extract complete message
            payload = bytes(self.buffer[FLOCK_HEADER_SIZE:msg_size])
            messages.append((header, payload))
            self.buffer = self.buffer[msg_size:]

        return messages

    def clear(self) -> None:
        """Clear the buffer."""
        self.buffer.clear()

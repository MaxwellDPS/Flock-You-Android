"""
Sample test data and fixtures for Flock Bridge FAP tests.

This module provides realistic test data for protocol and communication testing.
"""

import random
import struct
from typing import List, Tuple

# ============================================================================
# Sample WiFi Networks
# ============================================================================

SAMPLE_WIFI_NETWORKS = [
    {
        "ssid": "HomeNetwork",
        "bssid": bytes([0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33]),
        "rssi": -45,
        "channel": 1,
        "security": 3,  # WPA2
        "hidden": False,
    },
    {
        "ssid": "CoffeeShop_Guest",
        "bssid": bytes([0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E]),
        "rssi": -65,
        "channel": 6,
        "security": 0,  # Open
        "hidden": False,
    },
    {
        "ssid": "NETGEAR-5G",
        "bssid": bytes([0xC0, 0xFF, 0xEE, 0xBA, 0xBE, 0x01]),
        "rssi": -72,
        "channel": 36,
        "security": 4,  # WPA3
        "hidden": False,
    },
    {
        "ssid": "",  # Hidden network
        "bssid": bytes([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01]),
        "rssi": -80,
        "channel": 11,
        "security": 3,  # WPA2
        "hidden": True,
    },
]

# ============================================================================
# Sample Sub-GHz Detections
# ============================================================================

SAMPLE_SUBGHZ_DETECTIONS = [
    {
        "frequency": 433920000,
        "rssi": -40,
        "modulation": 5,  # OOK
        "duration_ms": 150,
        "bandwidth": 500000,
        "protocol_id": 1,
        "protocol_name": "Princeton",
    },
    {
        "frequency": 315000000,
        "rssi": -55,
        "modulation": 3,  # FSK
        "duration_ms": 200,
        "bandwidth": 250000,
        "protocol_id": 5,
        "protocol_name": "CAME",
    },
    {
        "frequency": 868350000,
        "rssi": -70,
        "modulation": 2,  # ASK
        "duration_ms": 100,
        "bandwidth": 100000,
        "protocol_id": 0,
        "protocol_name": "Unknown",
    },
    {
        "frequency": 915000000,
        "rssi": -45,
        "modulation": 6,  # GFSK
        "duration_ms": 50,
        "bandwidth": 125000,
        "protocol_id": 10,
        "protocol_name": "LoRa",
    },
]

# ============================================================================
# Sample BLE Devices
# ============================================================================

SAMPLE_BLE_DEVICES = [
    {
        "mac": bytes([0x11, 0x22, 0x33, 0x44, 0x55, 0x66]),
        "name": "iPhone",
        "rssi": -50,
        "address_type": 1,  # Random
        "connectable": False,
        "manufacturer_id": bytes([0x4C, 0x00]),  # Apple
    },
    {
        "mac": bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF]),
        "name": "AirTag",
        "rssi": -65,
        "address_type": 1,
        "connectable": False,
        "manufacturer_id": bytes([0x4C, 0x00]),  # Apple
    },
    {
        "mac": bytes([0x00, 0x11, 0x22, 0x33, 0x44, 0x55]),
        "name": "Tile Mate",
        "rssi": -75,
        "address_type": 0,  # Public
        "connectable": True,
        "manufacturer_id": bytes([0xAD, 0x01]),  # Tile
    },
    {
        "mac": bytes([0xF0, 0xE1, 0xD2, 0xC3, 0xB4, 0xA5]),
        "name": "",  # No name
        "rssi": -85,
        "address_type": 1,
        "connectable": False,
        "manufacturer_id": bytes([0x00, 0x00]),
    },
]

# ============================================================================
# Sample NFC Cards
# ============================================================================

SAMPLE_NFC_CARDS = [
    {
        "uid": bytes([0x04, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC]),
        "uid_len": 7,
        "nfc_type": 1,  # Type A
        "sak": 0x08,  # MIFARE Classic 1K
        "atqa": bytes([0x04, 0x00]),
        "type_name": "MIFARE Classic 1K",
    },
    {
        "uid": bytes([0x04, 0xAA, 0xBB, 0xCC]),
        "uid_len": 4,
        "nfc_type": 1,
        "sak": 0x00,  # Ultralight
        "atqa": bytes([0x44, 0x00]),
        "type_name": "Ultralight",
    },
    {
        "uid": bytes([0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66]),
        "uid_len": 7,
        "nfc_type": 1,
        "sak": 0x20,  # DESFire or payment card
        "atqa": bytes([0x03, 0x44]),
        "type_name": "DESFire",
    },
]

# ============================================================================
# Sample IR Signals
# ============================================================================

SAMPLE_IR_SIGNALS = [
    {
        "protocol_id": 1,
        "protocol_name": "NEC",
        "address": 0x04,
        "command": 0x08,  # Power
        "signal_strength": -20,
    },
    {
        "protocol_id": 2,
        "protocol_name": "Samsung",
        "address": 0x07,
        "command": 0x02,  # Volume Up
        "signal_strength": -35,
    },
    {
        "protocol_id": 3,
        "protocol_name": "Sony",
        "address": 0x01,
        "command": 0x15,  # Channel Up
        "signal_strength": -45,
    },
]

# ============================================================================
# Sample WIPS Alerts
# ============================================================================

SAMPLE_WIPS_ALERTS = [
    {
        "alert_type": 0,  # Evil Twin
        "severity": 0,  # Critical
        "ssid": "FreeWiFi",
        "description": "Potential evil twin AP detected with same SSID",
        "bssids": [
            bytes([0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33]),
            bytes([0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x34]),
        ],
    },
    {
        "alert_type": 1,  # Deauth Attack
        "severity": 1,  # High
        "ssid": "TargetNetwork",
        "description": "Deauthentication flood detected",
        "bssids": [
            bytes([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01]),
        ],
    },
    {
        "alert_type": 3,  # Hidden Network Strong
        "severity": 2,  # Medium
        "ssid": "",
        "description": "Strong hidden network detected",
        "bssids": [
            bytes([0x00, 0x11, 0x22, 0x33, 0x44, 0x55]),
        ],
    },
]

# ============================================================================
# Malformed Message Samples
# ============================================================================

MALFORMED_MESSAGES = [
    # Invalid version
    bytes([0x99, 0x00, 0x00, 0x00]),

    # Truncated header
    bytes([0x01, 0x00]),

    # Invalid payload length (larger than buffer)
    bytes([0x01, 0x00, 0xFF, 0xFF]),

    # Valid header but missing payload
    bytes([0x01, 0x05, 0x10, 0x00]),  # Claims 16 bytes payload

    # All zeros
    bytes([0x00, 0x00, 0x00, 0x00]),

    # All ones
    bytes([0xFF, 0xFF, 0xFF, 0xFF]),
]

# ============================================================================
# Helper Functions
# ============================================================================

def generate_random_mac() -> bytes:
    """Generate a random MAC address."""
    return bytes([random.randint(0, 255) for _ in range(6)])


def generate_random_ssid(max_len: int = 32) -> str:
    """Generate a random SSID."""
    length = random.randint(1, max_len)
    chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    return "".join(random.choice(chars) for _ in range(length))


def generate_random_uid(length: int = 7) -> bytes:
    """Generate a random NFC UID."""
    return bytes([random.randint(0, 255) for _ in range(length)])


def generate_random_frequency(band: str = "433") -> int:
    """Generate a random frequency for a given band."""
    bands = {
        "315": (310000000, 320000000),
        "433": (433050000, 434790000),
        "868": (863000000, 870000000),
        "915": (902000000, 928000000),
    }
    low, high = bands.get(band, bands["433"])
    return random.randint(low, high)


def create_stress_test_messages(count: int = 100) -> List[bytes]:
    """Create a list of random valid messages for stress testing."""
    import sys
    sys.path.insert(0, str(__file__).rsplit("/", 2)[0])
    from flock_protocol import FlockProtocol, FlockMessageType

    messages = []
    message_creators = [
        FlockProtocol.create_heartbeat,
        FlockProtocol.create_status_request,
        FlockProtocol.create_wifi_scan_request,
        FlockProtocol.create_ble_scan_request,
        FlockProtocol.create_ir_scan_request,
        FlockProtocol.create_nfc_scan_request,
        lambda: FlockProtocol.create_subghz_scan_request(
            generate_random_frequency(),
            generate_random_frequency()
        ),
        lambda: FlockProtocol.create_lf_probe(random.randint(100, 5000)),
        lambda: FlockProtocol.create_wifi_probe(generate_random_ssid()),
    ]

    for _ in range(count):
        creator = random.choice(message_creators)
        messages.append(creator())

    return messages

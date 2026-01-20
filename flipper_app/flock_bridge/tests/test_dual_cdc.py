#!/usr/bin/env python3
"""
Test dual CDC communication with Flock Bridge FAP.

In dual CDC mode:
- Channel 0 (/dev/cu.usbmodemflip_Ruciro1 or first port): CLI
- Channel 1 (/dev/cu.usbmodemflip_Ruciro3 or second port): Flock protocol

Usage:
1. Disconnect/reconnect Flipper
2. Launch Flock Bridge FAP manually on Flipper
3. Run this script
"""

import serial
import serial.tools.list_ports
import time
import struct
import sys

def find_flipper_ports():
    """Find all Flipper serial ports."""
    ports = []
    for port in serial.tools.list_ports.comports():
        if 'flip' in port.device.lower() or (port.vid == 1155 and port.pid in [22336, 22337]):
            ports.append(port.device)
    return sorted(ports)

def send_heartbeat(ser, timeout=1.0):
    """Send heartbeat and wait for response."""
    # Heartbeat: version=1, type=0, length=0
    heartbeat = struct.pack('<BBH', 1, 0, 0)

    ser.reset_input_buffer()
    ser.write(heartbeat)

    start = time.time()
    response = b''
    while time.time() - start < timeout:
        if ser.in_waiting > 0:
            data = ser.read(ser.in_waiting)
            response += data
            if len(response) >= 4:
                break
        time.sleep(0.05)

    return response

def test_cli(ser):
    """Test if this is a CLI port."""
    ser.reset_input_buffer()
    ser.write(b'\r\n')
    time.sleep(0.3)

    response = ser.read(1000)
    if b'>:' in response or b'Flipper' in response or b'help' in response:
        return True

    # Try help command
    ser.write(b'help\r\n')
    time.sleep(0.5)
    response = ser.read(2000)
    if b'?' in response or b'help' in response or b'Commands' in response:
        return True

    return False

def main():
    print("=" * 60)
    print("Flock Bridge Dual CDC Test")
    print("=" * 60)

    # Find ports
    ports = find_flipper_ports()
    print(f"\nFound Flipper ports: {ports}")

    if not ports:
        print("\nNo Flipper ports found!")
        print("Make sure Flipper is connected via USB.")

        print("\nAll available ports:")
        for port in serial.tools.list_ports.comports():
            print(f"  {port.device}: {port.description}")
        return 1

    # Analyze each port
    cli_port = None
    flock_port = None

    for port in ports:
        print(f"\n--- Testing {port} ---")
        try:
            ser = serial.Serial(port, 115200, timeout=1)
            time.sleep(0.3)

            # First test for Flock protocol
            print("  Sending heartbeat...")
            response = send_heartbeat(ser, timeout=1.0)

            if response:
                print(f"  Heartbeat response: {response.hex()}")
                if len(response) >= 4:
                    version, msg_type, length = struct.unpack('<BBH', response[:4])
                    print(f"  Parsed: version={version}, type={msg_type}, length={length}")
                    if version == 1 and msg_type == 0:
                        print(f"  ✓ This is the FLOCK PROTOCOL port!")
                        flock_port = port
            else:
                print("  No heartbeat response")

            # Test for CLI
            if not flock_port or port != flock_port:
                print("  Testing for CLI...")
                if test_cli(ser):
                    print(f"  ✓ This is the CLI port")
                    cli_port = port
                else:
                    print("  Not responding as CLI")

            ser.close()
        except Exception as e:
            print(f"  Error: {e}")

    print("\n" + "=" * 60)
    print("RESULTS")
    print("=" * 60)

    if len(ports) == 1:
        print("\nOnly one port found - Flipper is in SINGLE CDC mode.")
        print("The Flock Bridge FAP needs to be running to switch to dual CDC mode.")
        print("\nTo test:")
        print("1. Launch Flock Bridge FAP on your Flipper")
        print("2. Wait for it to switch to dual CDC mode")
        print("3. A second serial port should appear")
        print("4. Run this test again")

    if flock_port:
        print(f"\n✓ FLOCK PROTOCOL PORT: {flock_port}")
        print("  Use this port for Flock protocol communication.")
    else:
        print("\n✗ Flock protocol port NOT FOUND")
        print("  The FAP may not be running or USB is not properly configured.")

    if cli_port:
        print(f"\n✓ CLI PORT: {cli_port}")
        print("  Use this port for Flipper CLI commands.")
    else:
        print("\n✗ CLI port not responding")
        if len(ports) == 1 and not flock_port:
            print("  The Flipper might be in a hung state. Try restarting it.")

    # If we found the flock port, do a more thorough test
    if flock_port:
        print("\n" + "-" * 60)
        print("Running extended Flock protocol test...")
        try:
            ser = serial.Serial(flock_port, 115200, timeout=2)
            time.sleep(0.3)

            # Test multiple heartbeats
            for i in range(3):
                response = send_heartbeat(ser, timeout=1.0)
                if response:
                    print(f"  Heartbeat {i+1}: {response.hex()} ✓")
                else:
                    print(f"  Heartbeat {i+1}: no response ✗")
                time.sleep(0.2)

            ser.close()
            print("\nTest complete!")
            return 0
        except Exception as e:
            print(f"Error during extended test: {e}")
            return 1

    return 0 if flock_port else 1

if __name__ == '__main__':
    sys.exit(main())

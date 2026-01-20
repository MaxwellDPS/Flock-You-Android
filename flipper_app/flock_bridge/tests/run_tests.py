#!/usr/bin/env python3
"""
Flock Bridge FAP Test Runner

This script provides a convenient interface for running the FAP test suite.

Usage:
    # Run all unit tests (no Flipper required)
    python run_tests.py unit

    # Run e2e tests (Flipper required)
    python run_tests.py e2e --flipper-required

    # Run all tests with coverage
    python run_tests.py all --coverage

    # Run specific test file
    python run_tests.py file tests/unit/test_protocol.py

    # Run with verbose output
    python run_tests.py unit -v

    # List available Flipper ports
    python run_tests.py detect
"""

import argparse
import subprocess
import sys
import os
from pathlib import Path


def get_test_dir():
    """Get the tests directory path."""
    return Path(__file__).parent


def run_pytest(args: list, cwd: Path = None) -> int:
    """Run pytest with given arguments."""
    cmd = [sys.executable, "-m", "pytest"] + args
    print(f"Running: {' '.join(cmd)}")
    return subprocess.call(cmd, cwd=cwd or get_test_dir())


def run_unit_tests(extra_args: list = None) -> int:
    """Run unit tests only."""
    args = ["unit/", "-v"]
    if extra_args:
        args.extend(extra_args)
    return run_pytest(args)


def run_e2e_tests(extra_args: list = None, flipper_port: str = None) -> int:
    """Run end-to-end tests."""
    args = ["e2e/", "-v", "--flipper-required"]
    if flipper_port:
        args.extend(["--flipper-port", flipper_port])
    if extra_args:
        args.extend(extra_args)
    return run_pytest(args)


def run_all_tests(extra_args: list = None, with_coverage: bool = False) -> int:
    """Run all tests."""
    args = ["."]
    if with_coverage:
        args.extend(["--cov=.", "--cov-report=html", "--cov-report=term"])
    if extra_args:
        args.extend(extra_args)
    return run_pytest(args)


def run_specific_file(filepath: str, extra_args: list = None) -> int:
    """Run tests in a specific file."""
    args = [filepath]
    if extra_args:
        args.extend(extra_args)
    return run_pytest(args)


def detect_flipper() -> None:
    """Detect and list available Flipper devices."""
    try:
        from flipper_connection import FlipperConnection
    except ImportError:
        print("Error: flipper_connection module not found.")
        print("Make sure you're running from the tests directory.")
        sys.exit(1)

    print("Searching for Flipper Zero devices...")
    print()

    # List all serial ports
    ports = FlipperConnection.list_serial_ports()
    if ports:
        print("Available serial ports:")
        for device, description, vid_pid in ports:
            print(f"  {device}: {description} ({vid_pid})")
    else:
        print("No serial ports found.")

    print()

    # Try to find Flipper
    flipper_port = FlipperConnection.find_flipper_port()
    if flipper_port:
        print(f"Flipper Zero detected at: {flipper_port}")

        # Try to connect
        print("Attempting connection...")
        conn = FlipperConnection(port=flipper_port)
        try:
            conn.connect()
            print("Connection successful!")

            # Try to ping
            from flipper_connection import FlipperTestClient
            client = FlipperTestClient(conn)
            if client.ping(timeout=5.0):
                print("FAP is responding to heartbeat!")

                # Get status
                try:
                    status = client.get_status(timeout=5.0)
                    print(f"  Protocol version: {status.protocol_version}")
                    print(f"  Battery: {status.battery_percent}%")
                    print(f"  Uptime: {status.uptime_seconds}s")
                    print(f"  SubGHz ready: {status.subghz_ready}")
                    print(f"  BLE ready: {status.ble_ready}")
                    print(f"  IR ready: {status.ir_ready}")
                    print(f"  NFC ready: {status.nfc_ready}")
                except Exception as e:
                    print(f"  Could not get status: {e}")
            else:
                print("FAP not responding (is Flock Bridge running?)")

            conn.disconnect()
        except Exception as e:
            print(f"Connection failed: {e}")
    else:
        print("No Flipper Zero detected.")
        print("Make sure Flock Bridge FAP is running on your Flipper.")


def install_dependencies() -> int:
    """Install test dependencies."""
    requirements = get_test_dir() / "requirements.txt"
    if not requirements.exists():
        print(f"Requirements file not found: {requirements}")
        return 1

    cmd = [sys.executable, "-m", "pip", "install", "-r", str(requirements)]
    print(f"Running: {' '.join(cmd)}")
    return subprocess.call(cmd)


def main():
    parser = argparse.ArgumentParser(
        description="Flock Bridge FAP Test Runner",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )

    subparsers = parser.add_subparsers(dest="command", help="Test command")

    # Unit tests
    unit_parser = subparsers.add_parser("unit", help="Run unit tests")
    unit_parser.add_argument("-v", "--verbose", action="store_true")
    unit_parser.add_argument("-k", "--keyword", help="Run tests matching keyword")
    unit_parser.add_argument("extra", nargs="*", help="Additional pytest arguments")

    # E2E tests
    e2e_parser = subparsers.add_parser("e2e", help="Run end-to-end tests")
    e2e_parser.add_argument("--flipper-port", help="Flipper serial port")
    e2e_parser.add_argument("-v", "--verbose", action="store_true")
    e2e_parser.add_argument("-k", "--keyword", help="Run tests matching keyword")
    e2e_parser.add_argument("extra", nargs="*", help="Additional pytest arguments")

    # All tests
    all_parser = subparsers.add_parser("all", help="Run all tests")
    all_parser.add_argument("--coverage", action="store_true", help="Run with coverage")
    all_parser.add_argument("--flipper-port", help="Flipper serial port")
    all_parser.add_argument("-v", "--verbose", action="store_true")
    all_parser.add_argument("extra", nargs="*", help="Additional pytest arguments")

    # Specific file
    file_parser = subparsers.add_parser("file", help="Run specific test file")
    file_parser.add_argument("filepath", help="Path to test file")
    file_parser.add_argument("-v", "--verbose", action="store_true")
    file_parser.add_argument("extra", nargs="*", help="Additional pytest arguments")

    # Detect Flipper
    detect_parser = subparsers.add_parser("detect", help="Detect Flipper Zero")

    # Install dependencies
    install_parser = subparsers.add_parser("install", help="Install test dependencies")

    args = parser.parse_args()

    # Change to tests directory
    os.chdir(get_test_dir())

    if args.command == "unit":
        extra = args.extra or []
        if args.verbose:
            extra.append("-vv")
        if args.keyword:
            extra.extend(["-k", args.keyword])
        sys.exit(run_unit_tests(extra))

    elif args.command == "e2e":
        extra = args.extra or []
        if args.verbose:
            extra.append("-vv")
        if args.keyword:
            extra.extend(["-k", args.keyword])
        sys.exit(run_e2e_tests(extra, args.flipper_port))

    elif args.command == "all":
        extra = args.extra or []
        if args.verbose:
            extra.append("-vv")
        if args.flipper_port:
            extra.extend(["--flipper-port", args.flipper_port, "--flipper-required"])
        sys.exit(run_all_tests(extra, args.coverage))

    elif args.command == "file":
        extra = args.extra or []
        if args.verbose:
            extra.append("-vv")
        sys.exit(run_specific_file(args.filepath, extra))

    elif args.command == "detect":
        detect_flipper()

    elif args.command == "install":
        sys.exit(install_dependencies())

    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()

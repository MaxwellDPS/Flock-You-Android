"""
Flipper Connection - USB CDC communication harness for Flock Bridge FAP testing.

This module provides a reliable connection to the Flipper Zero over USB CDC,
handling message framing, timeouts, and reconnection.
"""

import serial
import serial.tools.list_ports
import time
import threading
import queue
import logging
from typing import Optional, Tuple, List, Callable
from contextlib import contextmanager

from .flock_protocol import (
    FlockProtocol, FlockMessageHeader, FlockMessageBuffer,
    FlockMessageType, FlockErrorCode, FlockStatusResponse,
    FLOCK_HEADER_SIZE, FLOCK_MAX_MESSAGE_SIZE
)

logger = logging.getLogger(__name__)


class FlipperConnectionError(Exception):
    """Raised when connection to Flipper fails."""
    pass


class FlipperTimeoutError(Exception):
    """Raised when a response times out."""
    pass


class FlipperConnection:
    """
    USB CDC connection to Flipper Zero running Flock Bridge FAP.

    This class handles:
    - Auto-detection of Flipper Zero USB device
    - Message framing and buffering
    - Asynchronous receive with callback support
    - Thread-safe send/receive operations
    """

    # Flipper Zero USB identifiers
    FLIPPER_VID = 0x0483  # STMicroelectronics
    FLIPPER_PID = 0x5740  # Flipper Zero CDC

    # Alternative VIDs/PIDs for different firmware
    FLIPPER_VID_ALT = 0x1209
    FLIPPER_PID_ALT = 0x7776

    def __init__(
        self,
        port: Optional[str] = None,
        baudrate: int = 115200,
        timeout: float = 1.0,
        auto_reconnect: bool = True
    ):
        """
        Initialize Flipper connection.

        Args:
            port: Serial port path (auto-detected if None)
            baudrate: Serial baud rate (default 115200)
            timeout: Read timeout in seconds
            auto_reconnect: Whether to auto-reconnect on connection loss
        """
        self.port = port
        self.baudrate = baudrate
        self.timeout = timeout
        self.auto_reconnect = auto_reconnect

        self._serial: Optional[serial.Serial] = None
        self._running = False
        self._rx_thread: Optional[threading.Thread] = None
        self._rx_queue: queue.Queue = queue.Queue()
        self._message_buffer = FlockMessageBuffer()
        self._lock = threading.RLock()

        # Callbacks for received messages
        self._message_callbacks: List[Callable[[FlockMessageHeader, bytes], None]] = []

    @classmethod
    def find_flipper_port(cls) -> Optional[str]:
        """Find the serial port for a connected Flipper Zero."""
        ports = serial.tools.list_ports.comports()

        for port in ports:
            # Check for Flipper Zero VID/PID
            if port.vid == cls.FLIPPER_VID and port.pid == cls.FLIPPER_PID:
                logger.info(f"Found Flipper Zero at {port.device}")
                return port.device
            if port.vid == cls.FLIPPER_VID_ALT and port.pid == cls.FLIPPER_PID_ALT:
                logger.info(f"Found Flipper Zero (alt) at {port.device}")
                return port.device

            # Also check by description
            if port.description and 'flipper' in port.description.lower():
                logger.info(f"Found Flipper Zero by description at {port.device}")
                return port.device

        return None

    @classmethod
    def list_serial_ports(cls) -> List[Tuple[str, str, str]]:
        """List all available serial ports with their descriptions."""
        ports = serial.tools.list_ports.comports()
        return [(p.device, p.description, f"VID:{p.vid:04X} PID:{p.pid:04X}" if p.vid else "N/A")
                for p in ports]

    def connect(self) -> bool:
        """
        Connect to the Flipper Zero.

        Returns:
            True if connection successful, False otherwise.
        """
        with self._lock:
            if self._serial and self._serial.is_open:
                return True

            # Auto-detect port if not specified
            port = self.port or self.find_flipper_port()
            if not port:
                logger.error("No Flipper Zero found. Available ports:")
                for p in self.list_serial_ports():
                    logger.error(f"  {p[0]}: {p[1]} ({p[2]})")
                raise FlipperConnectionError("Flipper Zero not found")

            try:
                self._serial = serial.Serial(
                    port=port,
                    baudrate=self.baudrate,
                    timeout=self.timeout,
                    write_timeout=self.timeout
                )
                self._serial.reset_input_buffer()
                self._serial.reset_output_buffer()
                logger.info(f"Connected to Flipper Zero at {port}")

                # Start receive thread
                self._running = True
                self._rx_thread = threading.Thread(target=self._rx_loop, daemon=True)
                self._rx_thread.start()

                return True

            except serial.SerialException as e:
                logger.error(f"Failed to connect to {port}: {e}")
                raise FlipperConnectionError(f"Failed to connect: {e}")

    def disconnect(self) -> None:
        """Disconnect from the Flipper Zero."""
        with self._lock:
            self._running = False

            if self._rx_thread:
                self._rx_thread.join(timeout=2.0)
                self._rx_thread = None

            if self._serial:
                try:
                    self._serial.close()
                except Exception:
                    pass
                self._serial = None

            self._message_buffer.clear()
            logger.info("Disconnected from Flipper Zero")

    def is_connected(self) -> bool:
        """Check if currently connected."""
        with self._lock:
            return self._serial is not None and self._serial.is_open

    def send(self, data: bytes) -> bool:
        """
        Send raw data to the Flipper.

        Args:
            data: Raw bytes to send

        Returns:
            True if send successful.
        """
        with self._lock:
            if not self._serial or not self._serial.is_open:
                raise FlipperConnectionError("Not connected")

            try:
                self._serial.write(data)
                self._serial.flush()
                logger.debug(f"Sent {len(data)} bytes: {data.hex()}")
                return True
            except serial.SerialException as e:
                logger.error(f"Send failed: {e}")
                if self.auto_reconnect:
                    self._handle_disconnect()
                raise FlipperConnectionError(f"Send failed: {e}")

    def send_message(self, msg_type: FlockMessageType, payload: bytes = b'') -> bool:
        """
        Send a protocol message to the Flipper.

        Args:
            msg_type: Message type
            payload: Message payload

        Returns:
            True if send successful.
        """
        message = FlockProtocol.create_message(msg_type, payload)
        return self.send(message)

    def receive(self, timeout: Optional[float] = None) -> Optional[Tuple[FlockMessageHeader, bytes]]:
        """
        Receive a single message from the queue.

        Args:
            timeout: Receive timeout in seconds (None = use default)

        Returns:
            Tuple of (header, payload) or None if timeout.
        """
        timeout = timeout if timeout is not None else self.timeout
        try:
            return self._rx_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def send_and_receive(
        self,
        msg_type: FlockMessageType,
        payload: bytes = b'',
        expected_type: Optional[FlockMessageType] = None,
        timeout: float = 5.0
    ) -> Tuple[FlockMessageHeader, bytes]:
        """
        Send a message and wait for a response.

        Args:
            msg_type: Message type to send
            payload: Message payload
            expected_type: Expected response type (None = accept any)
            timeout: Response timeout in seconds

        Returns:
            Tuple of (header, payload) for the response.

        Raises:
            FlipperTimeoutError: If no response received within timeout.
        """
        # Clear any pending messages
        while not self._rx_queue.empty():
            try:
                self._rx_queue.get_nowait()
            except queue.Empty:
                break

        # Send message
        self.send_message(msg_type, payload)

        # Wait for response
        start_time = time.time()
        while time.time() - start_time < timeout:
            remaining = timeout - (time.time() - start_time)
            result = self.receive(timeout=min(remaining, 0.5))
            if result:
                header, response_payload = result
                if expected_type is None or header.msg_type == expected_type:
                    return result
                # Handle error responses
                if header.msg_type == FlockMessageType.ERROR:
                    error_code, error_msg = FlockProtocol.parse_error(response_payload)
                    raise FlipperConnectionError(f"Error response: {error_code.name}: {error_msg}")
                # Keep waiting for expected type
                logger.debug(f"Ignoring unexpected message type: {header.msg_type}")

        raise FlipperTimeoutError(f"No response received within {timeout}s")

    def add_message_callback(
        self,
        callback: Callable[[FlockMessageHeader, bytes], None]
    ) -> None:
        """Add a callback for received messages."""
        self._message_callbacks.append(callback)

    def remove_message_callback(
        self,
        callback: Callable[[FlockMessageHeader, bytes], None]
    ) -> None:
        """Remove a message callback."""
        try:
            self._message_callbacks.remove(callback)
        except ValueError:
            pass

    def _rx_loop(self) -> None:
        """Background thread for receiving data."""
        while self._running:
            try:
                if not self._serial or not self._serial.is_open:
                    time.sleep(0.1)
                    continue

                # Read available data
                if self._serial.in_waiting > 0:
                    data = self._serial.read(self._serial.in_waiting)
                    if data:
                        logger.debug(f"Received {len(data)} bytes: {data.hex()}")
                        self._message_buffer.append(data)

                        # Extract complete messages
                        messages = self._message_buffer.get_messages()
                        for header, payload in messages:
                            logger.debug(f"Parsed message: type={header.msg_type}, len={header.payload_length}")
                            self._rx_queue.put((header, payload))

                            # Notify callbacks
                            for callback in self._message_callbacks:
                                try:
                                    callback(header, payload)
                                except Exception as e:
                                    logger.error(f"Callback error: {e}")
                else:
                    time.sleep(0.01)

            except serial.SerialException as e:
                logger.error(f"Receive error: {e}")
                if self.auto_reconnect:
                    self._handle_disconnect()
                break
            except Exception as e:
                logger.error(f"RX loop error: {e}")
                time.sleep(0.1)

    def _handle_disconnect(self) -> None:
        """Handle unexpected disconnection."""
        with self._lock:
            if self._serial:
                try:
                    self._serial.close()
                except Exception:
                    pass
                self._serial = None
            logger.warning("Connection lost")

    @contextmanager
    def session(self):
        """Context manager for connection session."""
        self.connect()
        try:
            yield self
        finally:
            self.disconnect()


class FlipperTestClient:
    """
    High-level test client for Flock Bridge FAP.

    Provides convenient methods for testing FAP functionality.
    """

    def __init__(self, connection: Optional[FlipperConnection] = None):
        """
        Initialize test client.

        Args:
            connection: Existing connection or None to create new one.
        """
        self.connection = connection or FlipperConnection()
        self._received_messages: List[Tuple[FlockMessageHeader, bytes]] = []

    def connect(self) -> bool:
        """Connect to Flipper."""
        return self.connection.connect()

    def disconnect(self) -> None:
        """Disconnect from Flipper."""
        self.connection.disconnect()

    def ping(self, timeout: float = 2.0) -> bool:
        """
        Send heartbeat and verify response.

        Returns:
            True if heartbeat response received.
        """
        try:
            header, _ = self.connection.send_and_receive(
                FlockMessageType.HEARTBEAT,
                expected_type=FlockMessageType.HEARTBEAT,
                timeout=timeout
            )
            return True
        except (FlipperTimeoutError, FlipperConnectionError):
            return False

    def get_status(self, timeout: float = 5.0) -> FlockStatusResponse:
        """
        Request and return device status.

        Returns:
            FlockStatusResponse with current device state.
        """
        header, payload = self.connection.send_and_receive(
            FlockMessageType.STATUS_REQUEST,
            expected_type=FlockMessageType.STATUS_RESPONSE,
            timeout=timeout
        )
        return FlockProtocol.parse_status_response(payload)

    def request_wifi_scan(self, timeout: float = 10.0) -> Optional[Tuple[int, list]]:
        """
        Request WiFi scan and wait for result.

        Returns:
            Tuple of (timestamp, networks) or None if no result.
        """
        self.connection.send_message(FlockMessageType.WIFI_SCAN_REQUEST)
        try:
            header, payload = self.connection.send_and_receive(
                FlockMessageType.WIFI_SCAN_REQUEST,
                expected_type=FlockMessageType.WIFI_SCAN_RESULT,
                timeout=timeout
            )
            return FlockProtocol.parse_wifi_result(payload)
        except FlipperTimeoutError:
            return None

    def request_subghz_scan(
        self,
        frequency_start: int = 300000000,
        frequency_end: int = 928000000,
        timeout: float = 10.0
    ) -> Optional[Tuple[int, int, int, list]]:
        """
        Request Sub-GHz scan and wait for result.

        Returns:
            Tuple of (timestamp, freq_start, freq_end, detections) or None.
        """
        payload = FlockProtocol.create_subghz_scan_request(frequency_start, frequency_end)
        try:
            header, response = self.connection.send_and_receive(
                FlockMessageType.SUBGHZ_SCAN_REQUEST,
                payload[FLOCK_HEADER_SIZE:],  # Strip header
                expected_type=FlockMessageType.SUBGHZ_SCAN_RESULT,
                timeout=timeout
            )
            return FlockProtocol.parse_subghz_result(response)
        except FlipperTimeoutError:
            return None

    def send_lf_probe(self, duration_ms: int = 1000, timeout: float = 5.0) -> Tuple[bool, str]:
        """
        Send LF probe command.

        Returns:
            Tuple of (success, message).
        """
        payload = FlockProtocol.create_lf_probe(duration_ms)
        try:
            header, response = self.connection.send_and_receive(
                FlockMessageType.LF_PROBE_TX,
                payload[FLOCK_HEADER_SIZE:],
                timeout=timeout
            )
            if header.msg_type == FlockMessageType.ERROR:
                error_code, msg = FlockProtocol.parse_error(response)
                return (False, f"{error_code.name}: {msg}")
            return (True, "OK")
        except FlipperTimeoutError:
            return (False, "Timeout")

    def send_ir_strobe(
        self,
        frequency_hz: int = 14,
        duty_cycle: int = 50,
        duration_ms: int = 1000,
        timeout: float = 5.0
    ) -> Tuple[bool, str]:
        """
        Send IR strobe command.

        Returns:
            Tuple of (success, message).
        """
        payload = FlockProtocol.create_ir_strobe(frequency_hz, duty_cycle, duration_ms)
        try:
            header, response = self.connection.send_and_receive(
                FlockMessageType.IR_STROBE_TX,
                payload[FLOCK_HEADER_SIZE:],
                timeout=timeout
            )
            if header.msg_type == FlockMessageType.ERROR:
                error_code, msg = FlockProtocol.parse_error(response)
                return (False, f"{error_code.name}: {msg}")
            return (True, "OK")
        except FlipperTimeoutError:
            return (False, "Timeout")

    def send_invalid_message(self, data: bytes) -> Optional[Tuple[FlockMessageHeader, bytes]]:
        """
        Send raw invalid data and capture response.

        Useful for testing error handling.
        """
        self.connection.send(data)
        return self.connection.receive(timeout=2.0)

    def send_oversized_payload(self, size: int = 3000) -> Optional[Tuple[FlockMessageHeader, bytes]]:
        """
        Send a message with oversized payload to test validation.

        Args:
            size: Payload size to send (should trigger error if > FLOCK_MAX_PAYLOAD_SIZE)
        """
        # Manually construct header with oversized payload_length
        import struct
        header = struct.pack('<BBH', 1, FlockMessageType.HEARTBEAT, size)
        fake_payload = b'\x00' * min(size, 100)  # Only send partial payload
        self.connection.send(header + fake_payload)
        return self.connection.receive(timeout=2.0)

    def collect_messages(self, duration: float = 5.0) -> List[Tuple[FlockMessageHeader, bytes]]:
        """
        Collect all messages received during a time period.

        Args:
            duration: Time to collect messages in seconds.

        Returns:
            List of (header, payload) tuples.
        """
        messages = []
        start = time.time()
        while time.time() - start < duration:
            msg = self.connection.receive(timeout=0.5)
            if msg:
                messages.append(msg)
        return messages

    @contextmanager
    def session(self):
        """Context manager for test session."""
        self.connect()
        try:
            yield self
        finally:
            self.disconnect()

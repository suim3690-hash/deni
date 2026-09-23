"""Bounded, newline-delimited JSON messages for a trusted LAN prototype."""
import json
import time

COMMANDS = {"F": "Forward", "B": "Backward", "L": "Left", "R": "Right", "S": "Stop"}
LEASE_SECONDS = 0.6


def send_message(sock, message):
    sock.settimeout(0.2)
    sock.sendall((json.dumps(message, separators=(",", ":")) + "\n").encode("ascii"))


def receive_message(sock, timeout):
    deadline = time.monotonic() + timeout
    data = bytearray()
    while len(data) < 512:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Message deadline expired")
        sock.settimeout(remaining)
        byte = sock.recv(1)
        if not byte:
            raise ConnectionError("Peer disconnected")
        if byte == b"\n":
            result = json.loads(data.decode("ascii"))
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        data.extend(byte)
    raise ValueError("Message too long")

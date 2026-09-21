"""Synchronous PC API. Call drive every 0.2 s while motion is authorized.

No background thread repeats stale decisions. A stalled caller loses its lease.
One client and one thread may own a connection. Reconnect explicitly after errors.
"""
import socket
from protocol import COMMANDS, receive_message, send_message


class RobotClient:
    def __init__(self, host, token, port=8765):
        self.sock = socket.create_connection((host, port), timeout=3.0)
        self.seq = 0
        self.failed = False
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            send_message(self.sock, {"type": "hello", "token": token})
            response = receive_message(self.sock, 2.0)
            if response.get("ready") is not True:
                raise RuntimeError(response.get("error", "Receiver not ready"))
        except BaseException:
            self.sock.close()
            raise

    def drive(self, command):
        if self.failed:
            raise RuntimeError("Connection failed; start a new stopped session")
        try:
            if command not in COMMANDS:
                raise ValueError("Use F, B, L, R or S")
            self.seq += 1
            send_message(self.sock, {"command": command, "seq": self.seq})
            response = receive_message(self.sock, 0.5)
            if response.get("ack") != command or response.get("seq") != self.seq:
                raise RuntimeError("Unexpected command acknowledgement")
            return response
        except BaseException:
            self.failed = True
            self.sock.close()
            raise

    def close(self):
        try:
            if not self.failed:
                self.drive("S")
        except (OSError, ValueError, RuntimeError):
            pass
        finally:
            self.failed = True
            self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()

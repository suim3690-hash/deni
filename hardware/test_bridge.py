"""Local HTTP/WebSocket contract tests; no Spring server, DB or robot required."""
import asyncio
import json
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

from websockets.asyncio.server import serve
from bridge import Bridge


class Contracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / "transport.sqlite3")
        self.bridge = Bridge("robot-test", "x" * 32, "http://localhost", "ws://localhost", self.path)

    def tearDown(self):
        self.bridge.close()
        self.temp.cleanup()

    def test_http_multipart_retry_and_conflict(self):
        received = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                message = BytesParser(policy=default).parsebytes(
                    ("Content-Type: " + self.headers["Content-Type"] + "\r\n\r\n").encode() + raw)
                fields = {part.get_param("name", header="content-disposition"): part.get_payload(decode=True) for part in message.iter_parts()}
                received.append((self.path, self.headers, fields))
                self.send_response(503 if len(received) == 1 else 200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"eventId": fields["eventId"].decode(), "hazardId": None}).encode())
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.bridge.http_url = "http://127.0.0.1:" + str(server.server_port)
            event = self.bridge.enqueue_detection(b"\xff\xd8\xfftest", "동전", "HAZARD")
            self.bridge.flush_once()
            self.assertEqual(self.bridge.db.execute("SELECT status FROM events").fetchone()[0], "pending")
            self.bridge.flush_once()
            self.assertEqual(self.bridge.db.execute("SELECT status FROM events").fetchone()[0], "sent")
            self.assertEqual(received[0][2], received[1][2])
            self.assertEqual(received[0][0], "/api/v1/hardware/detections")
            self.assertEqual(received[0][1]["Authorization"], "Bearer " + "x" * 32)
            self.assertEqual(received[0][1]["X-Device-Id"], "robot-test")
            self.assertEqual(received[0][2]["objectLabel"].decode(), "동전")
            with self.assertRaises(ValueError):
                self.bridge.enqueue_detection(b"\xff\xd8\xffdifferent", "동전", "HAZARD", event)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_socket_state_ack_failure_and_restart_deduplication(self):
        async def scenario():
            command = str(uuid4())
            request = {"type": "COMMAND", "payload": {"commandId": command, "command": "PAUSE",
                "expiresAt": (datetime.now(timezone.utc) + timedelta(seconds=10)).isoformat(), "parameters": {}}}
            reports = []
            done = asyncio.get_running_loop().create_future()
            async def handler(socket):
                try:
                    self.assertEqual(socket.request.path, "/ws/devices")
                    self.assertEqual(socket.request.headers["X-Device-Id"], "robot-test")
                    self.assertEqual(socket.request.headers["Authorization"], "Bearer " + "x" * 32)
                    state = json.loads(await socket.recv())
                    self.assertEqual(state["type"], "ROBOT_STATE")
                    self.assertEqual(state["payload"]["operationState"], "UNKNOWN")
                    self.assertIsNone(state["payload"]["batteryPercent"])
                    await socket.send(json.dumps(request))
                    while len(reports) < 2:
                        msg = json.loads(await socket.recv())
                        if msg["type"] != "ROBOT_STATE":
                            reports.append(msg)
                    done.set_result(None)
                except BaseException as exc:
                    done.set_exception(exc)
            async with serve(handler, "127.0.0.1", 0) as server:
                self.bridge.ws_url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/ws/devices"
                await asyncio.wait_for(self.bridge.session(), 5)
                await done
            self.assertEqual([r["type"] for r in reports], ["COMMAND_ACK", "COMMAND_RESULT"])
            result = reports[1]["payload"]
            self.assertEqual(result["status"], "FAILED")
            self.assertEqual(result["errorCode"], "HARDWARE_NOT_CONNECTED")
            self.bridge.close()
            self.bridge = Bridge("robot-test", "x" * 32, "http://localhost", "ws://localhost", self.path)
            class Capture:
                def __init__(self): self.messages = []
                async def send(self, raw): self.messages.append(json.loads(raw))
            capture = Capture()
            await self.bridge.handle(capture, request)
            self.assertEqual(capture.messages[0]["payload"], result)
            expired = {"type": "COMMAND", "payload": dict(request["payload"], commandId=str(uuid4()), expiresAt="2000-01-01T00:00:00Z")}
            await self.bridge.handle(capture, expired)
            self.assertEqual(len(capture.messages), 1)
        asyncio.run(scenario())

    def test_injected_state_and_command_handler_reach_the_socket(self):
        async def scenario():
            observed = {"operationState": "UNKNOWN", "movementState": "FORWARD"}
            calls = []
            def provider():
                return dict(observed)
            def handler(command, command_id):
                calls.append((command, command_id))
                if command != "PAUSE":
                    return dict(status="FAILED", errorCode="UNSUPPORTED_COMMAND")
                observed.update(operationState="PAUSED", movementState="STOPPED")
                return dict(status="SUCCEEDED", operationState="PAUSED")
            bridge = Bridge("robot-test", "x" * 32, "http://localhost", "ws://localhost",
                            self.path, provider, handler)

            class Capture:
                def __init__(self): self.messages = []
                async def send(self, raw): self.messages.append(json.loads(raw))

            # Observed fields are sent; unmeasured ones stay null rather than guessed.
            state = await bridge.robot_state()
            self.assertEqual(state["movementState"], "FORWARD")
            self.assertIsNone(state["batteryPercent"])
            self.assertIsNone(state["movementDistanceM"])

            capture = Capture()
            command = str(uuid4())
            request = {"type": "COMMAND", "payload": {"commandId": command, "command": "PAUSE",
                "expiresAt": (datetime.now(timezone.utc) + timedelta(seconds=10)).isoformat()}}
            await bridge.handle(capture, request)
            self.assertEqual([m["type"] for m in capture.messages], ["COMMAND_ACK", "COMMAND_RESULT"])
            result = capture.messages[1]["payload"]
            self.assertEqual((result["status"], result["operationState"]), ("SUCCEEDED", "PAUSED"))
            self.assertEqual(calls, [("PAUSE", command)])
            self.assertEqual((await bridge.robot_state())["movementState"], "STOPPED")

            # A repeat of the same command replays the stored result without re-running it.
            await bridge.handle(capture, request)
            self.assertEqual(capture.messages[2]["payload"], result)
            self.assertEqual(len(calls), 1)

            other = {"type": "COMMAND", "payload": dict(request["payload"], commandId=str(uuid4()), command="RESUME")}
            await bridge.handle(capture, other)
            self.assertEqual(capture.messages[-1]["payload"]["errorCode"], "UNSUPPORTED_COMMAND")
            bridge.close()
        asyncio.run(scenario())

    def test_contract_violations_are_refused_before_sending(self):
        async def scenario():
            opened = []
            def bridge_with(provider=None, handler=None):
                bridge = Bridge("robot-test", "x" * 32, "http://localhost", "ws://localhost",
                                self.path, provider, handler)
                opened.append(bridge)
                return bridge

            class Capture:
                def __init__(self): self.messages = []
                async def send(self, raw): self.messages.append(json.loads(raw))

            try:
                with self.assertRaises(ValueError):
                    await bridge_with(provider=lambda: {"movementState": "SPINNING"}).robot_state()
                with self.assertRaises(ValueError):
                    await bridge_with(provider=lambda: {"batteryPercent": 50, "speed": 1}).robot_state()

                # The backend stores SUCCEEDED only with PAUSED, so never send that pair.
                claim = dict(status="SUCCEEDED", operationState="RUNNING")
                request = {"type": "COMMAND", "payload": {"commandId": str(uuid4()), "command": "PAUSE",
                    "expiresAt": (datetime.now(timezone.utc) + timedelta(seconds=10)).isoformat()}}
                with self.assertRaises(ValueError):
                    await bridge_with(handler=lambda *_: claim).handle(Capture(), request)
                with self.assertRaises(ValueError):
                    await bridge_with(handler=lambda *_: dict(status="PENDING")).handle(Capture(), request)
            finally:
                for bridge in opened:
                    bridge.close()
        asyncio.run(scenario())

    def test_detector_events_reach_local_receiver_per_object(self):
        import mock_backend
        from uploader import enqueue_frame
        from unittest.mock import patch
        server = ThreadingHTTPServer(('127.0.0.1', 0), mock_backend.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.bridge.http_url = f'http://127.0.0.1:{server.server_port}'
            local_id = str(uuid4())
            objects = [{'model': 'object', 'label': 'coin'}, {'model': 'object', 'label': 'battery'}]
            ids = enqueue_frame(self.bridge, local_id, b'\xff\xd8\xfftest', objects, 'object')
            self.assertEqual(ids, enqueue_frame(self.bridge, local_id, b'\xff\xd8\xfftest', objects, 'object'))
            self.assertEqual(len(set(ids)), 2)
            with patch.object(mock_backend, 'TOKEN', 'x' * 32):
                self.bridge.flush_once()
            self.assertEqual(mock_backend.EVENTS[ids[0]][0], '동전')
            self.assertEqual(mock_backend.EVENTS[ids[1]][0], '배터리')
            self.assertEqual(self.bridge.db.execute("SELECT COUNT(*) FROM events WHERE status='sent'").fetchone()[0], 2)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()

"""PC transport adapter for deni v2; no DB access or simulated motor success."""
import argparse
import asyncio
import json
import logging
import inspect
import os
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4

import requests
from websockets.asyncio.client import connect
from websockets.exceptions import WebSocketException

LOG = logging.getLogger("hardware")

OPERATION_STATES = {"RUNNING", "PAUSED", "RELOCATING", "UNKNOWN"}
MOVEMENT_STATES = {"FORWARD", "TURNING", "BACKWARD", "STOPPED", "UNKNOWN"}
# Unmeasured fields stay null; the backend stores them as-is and must not receive estimates.
UNOBSERVED_STATE = {"operationState": "UNKNOWN", "movementState": "UNKNOWN",
                    "batteryPercent": None, "movementDurationMs": None,
                    "movementDistanceM": None, "powerEnabled": None, "taskState": None}
STATE_PERIOD = 1.0


def now():
    return datetime.now(timezone.utc).isoformat()


class Bridge:
    def __init__(self, device, token, http_url, ws_url, store,
                 state_provider=None, command_handler=None, contact_handler=None):
        """state_provider/command_handler are synchronous; they run off the socket loop.

        Without them the socket still runs, reporting UNKNOWN and failing every
        command, which is what an unattached transport can honestly claim.
        contact_handler is called on the socket loop for every backend message,
        so it must be cheap; the robot uses it to stop when the backend goes silent.
        """
        if not device or len(token) < 32:
            raise ValueError("Registered device ID and token of at least 32 characters required")
        self.device, self.ws_url = device, ws_url
        self.state_provider, self.command_handler = state_provider, command_handler
        self.contact_handler = contact_handler
        self.http_url = http_url.rstrip("/")
        self.headers = {"Authorization": "Bearer " + token, "X-Device-Id": device}
        Path(store).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(store, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, device TEXT, model TEXT, label TEXT, image BLOB, mime TEXT, status TEXT, response TEXT)")
        self.db.execute("CREATE TABLE IF NOT EXISTS commands (id TEXT, device TEXT, result TEXT, PRIMARY KEY(id,device))")
        self.db.execute("CREATE TABLE IF NOT EXISTS pending_results (id TEXT, device TEXT, PRIMARY KEY(id,device))")
        self.db.execute("CREATE TABLE IF NOT EXISTS started_commands (id TEXT, device TEXT, PRIMARY KEY(id,device))")
        self.inflight = set()
        self.handlers = set()
        self.receipts = {}
        if command_handler is not None:
            # A process restart cannot safely repeat an unfinished physical task.
            for (identity,) in self.db.execute("SELECT id FROM started_commands WHERE device=?", (device,)).fetchall():
                result = dict(commandId=identity, status='FAILED', operationState='UNKNOWN',
                              completedAt=now(), errorCode='PROCESS_RESTARTED')
                self.db.execute('INSERT OR IGNORE INTO commands VALUES (?,?,?)', (identity,device,json.dumps(result)))
                self.db.execute('INSERT OR IGNORE INTO pending_results VALUES (?,?)', (identity,device))
            self.db.execute('DELETE FROM started_commands WHERE device=?', (device,))
        self.db.commit()

    def close(self):
        self.db.close()

    def enqueue_detection(self, image_bytes, label, model_type, event_id=None):
        """Call from the detector with an annotated JPEG/PNG; returns stable UUID.

        One writer process/thread owns each Bridge. Run inference separately
        from the socket loop, or hand results through a queue to that loop.
        """
        event = str(UUID(str(event_id))) if event_id else str(uuid4())
        if model_type not in {"HAZARD", "OBJECT"} or not label.strip() or len(label) > 100:
            raise ValueError("Invalid model type or label")
        if not 0 < len(image_bytes) <= 5 * 1024 * 1024:
            raise ValueError("Image must be 1 byte to 5 MiB")
        mime = ("image/png" if image_bytes.startswith(b"\x89PNG\r\n\x1a\n") else
                "image/jpeg" if image_bytes.startswith(b"\xff\xd8\xff") else None)
        if not mime:
            raise ValueError("JPEG or PNG required")
        existing = self.db.execute("SELECT device,model,label,image,mime FROM events WHERE id=?", (event,)).fetchone()
        data = (self.device, model_type, label, image_bytes, mime)
        if existing is not None and existing != data:
            raise ValueError("Event ID already belongs to different content")
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO events VALUES (?,?,?,?,?,?,?,?)",
                            (event, *data, "pending", None))
        return event

    def flush_once(self):
        """Retry network/408/429/5xx failures with original bytes and ID.

        Other HTTP failures and unexpected responses remain in the store as
        rejected for inspection; never create a replacement event automatically.
        """
        rows = self.db.execute("SELECT id,model,label,image,mime FROM events WHERE status='pending' AND device=? ORDER BY rowid LIMIT 20", (self.device,)).fetchall()
        for event, model, label, frame, mime in rows:
            try:
                response = requests.post(self.http_url + "/api/v1/hardware/detections",
                    headers=self.headers, data={"eventId": event, "modelType": model, "objectLabel": label},
                    files={"image": ("annotated.png" if mime == "image/png" else "annotated.jpg", frame, mime)},
                    timeout=(5, 20), allow_redirects=False)
            except requests.RequestException:
                LOG.warning("Upload unavailable; retained event %s", event)
                break
            if response.status_code in {408, 429} or response.status_code >= 500:
                LOG.warning("Upload retry pending: HTTP %s", response.status_code)
                break
            try:
                body = response.json()
                accepted = response.status_code == 200 and isinstance(body, dict) and body.get("eventId") == event and "hazardId" in body
            except ValueError:
                body, accepted = None, False
            status = "sent" if accepted else "rejected"
            with self.db:
                self.db.execute("UPDATE events SET status=?,response=? WHERE id=?",
                    (status, json.dumps({"httpStatus": response.status_code, "body": body}, ensure_ascii=False), event))
            LOG.info("Event %s: %s (HTTP %s)", event, status, response.status_code)

    async def send(self, socket, kind, payload):
        identity = str(uuid4())
        if kind == 'COMMAND_RESULT': self.receipts[identity] = payload['commandId']
        await socket.send(json.dumps({"type": kind, "messageId": identity,
            "deviceId": self.device, "sentAt": now(), "payload": payload}))

    async def robot_state(self):
        """Provider returns observed fields only; the rest stay null rather than guessed."""
        if self.state_provider is None:
            return dict(UNOBSERVED_STATE, sampledAt=now())
        observed = await asyncio.to_thread(self.state_provider)
        unknown = set(observed) - set(UNOBSERVED_STATE)
        if unknown:
            raise ValueError("State provider returned fields outside the contract: " + ", ".join(sorted(unknown)))
        state = dict(UNOBSERVED_STATE, **observed)
        if state["operationState"] not in OPERATION_STATES or state["movementState"] not in MOVEMENT_STATES:
            raise ValueError("State provider returned a state the backend rejects")
        # sampledAt is set after reading, so it describes this observation.
        return dict(state, sampledAt=now())

    async def report(self, socket):
        """Backend guide: send on change and about every second, so state never goes stale."""
        previous, sent_at = None, 0.0
        while True:
            state = await self.robot_state()
            observed = {key: value for key, value in state.items() if key != "sampledAt"}
            if observed != previous or time.monotonic()-sent_at >= STATE_PERIOD:
                await self.send(socket, "ROBOT_STATE", state)
                previous, sent_at = observed, time.monotonic()
            await asyncio.sleep(0.1)

    async def execute(self, command_id, command, parameters=None):
        """SUCCEEDED requires the hardware to confirm PAUSED; the backend rejects it otherwise."""
        if self.command_handler is None:
            return {"commandId": command_id, "status": "FAILED", "operationState": "UNKNOWN",
                "completedAt": now(),
                "errorCode": "HARDWARE_NOT_CONNECTED" if command == "PAUSE" else "UNSUPPORTED_COMMAND"}
        try:
            inspect.signature(self.command_handler).bind(command, command_id, parameters or {})
            accepts_parameters = True
        except TypeError:
            accepts_parameters = False
        if not accepts_parameters:
            outcome = await asyncio.to_thread(self.command_handler, command, command_id)
        else:
            outcome = await asyncio.to_thread(self.command_handler, command, command_id, parameters or {})
        result = {"commandId": command_id, "status": outcome["status"],
            "operationState": outcome.get("operationState", "UNKNOWN"), "completedAt": now(),
            "errorCode": outcome.get("errorCode")}
        if result["status"] not in {"SUCCEEDED", "FAILED"}:
            raise ValueError("Command handler returned an unsupported status")
        if result["status"] == "SUCCEEDED" and command in {'PAUSE','POWER_OFF'} and result["operationState"] != "PAUSED":
            raise ValueError("SUCCEEDED requires a confirmed PAUSED state")
        if result["operationState"] not in OPERATION_STATES:
            raise ValueError("Command handler returned a state the backend rejects")
        for key in ('hazardId','hazardPresent','absenceDurationMs','relocationCompleted'):
            if key in outcome: result[key] = outcome[key]
        return result

    async def handle(self, socket, message):
        if message.get("type") == "RECEIPT":
            if message.get("accepted") is True:
                command = self.receipts.pop(message.get('messageId'), None)
                if command:
                    with self.db:
                        self.db.execute('DELETE FROM pending_results WHERE id=? AND device=?', (command,self.device))
                LOG.info("WS RECEIPT accepted=true messageId=%s", message.get("messageId"))
            else:
                LOG.error("Backend receipt was not accepted")
            return
        if message.get("type") == "ERROR":
            LOG.error("Backend rejected a message: %s", message.get("code"))
            return
        if message.get("type") != "COMMAND":
            return
        payload = message["payload"]
        command_id = str(UUID(payload["commandId"]))
        if command_id in self.inflight: return
        cached = self.db.execute("SELECT result FROM commands WHERE id=? AND device=?", (command_id, self.device)).fetchone()
        if cached:
            await self.send(socket, "COMMAND_RESULT", json.loads(cached[0]))
            return
        expires = datetime.fromisoformat(payload["expiresAt"].replace("Z", "+00:00"))
        if expires.tzinfo is None:
            raise ValueError("Command expiresAt requires timezone")
        if expires <= datetime.now(timezone.utc):
            LOG.warning("Expired command ignored: %s", command_id)
            return
        self.inflight.add(command_id)
        try:
            await self.send(socket, "COMMAND_ACK", {"commandId": command_id, "status": "DELIVERED"})
            with self.db:
                self.db.execute('INSERT OR IGNORE INTO started_commands VALUES (?,?)', (command_id,self.device))
            result = await self.execute(command_id, payload["command"], payload.get('parameters', {}))
            with self.db:
                self.db.execute("INSERT INTO commands VALUES (?,?,?)", (command_id, self.device, json.dumps(result)))
                self.db.execute('INSERT OR IGNORE INTO pending_results VALUES (?,?)', (command_id,self.device))
                self.db.execute('DELETE FROM started_commands WHERE id=? AND device=?', (command_id,self.device))
            try:
                await self.send(socket, "COMMAND_RESULT", result)
            except (OSError, WebSocketException):
                LOG.warning('Result retained for reconnect: %s', command_id)
        finally:
            self.inflight.discard(command_id)

    async def session(self):
        async with connect(self.ws_url, additional_headers=self.headers,
                           open_timeout=10, ping_interval=20, ping_timeout=20, max_size=16384) as socket:
            LOG.info("WebSocket connected")
            self.receipts.clear()
            reporter = asyncio.create_task(self.report(socket))
            async def receive():
                async for raw in socket:
                    if self.contact_handler is not None:
                        self.contact_handler()
                    try:
                        message = json.loads(raw)
                        if message.get('type') == 'COMMAND':
                            async def process(message=message):
                                try: await self.handle(socket, message)
                                except Exception: LOG.exception('Command processing failed')
                            task = asyncio.create_task(process())
                            self.handlers.add(task)
                            task.add_done_callback(self.handlers.discard)
                        else:
                            await self.handle(socket, message)
                    except (ValueError, KeyError, TypeError, AttributeError):
                        LOG.error("Malformed server message ignored")
            receiver = asyncio.create_task(receive())
            async def resend():
                while True:
                    rows = self.db.execute('SELECT c.result FROM commands c JOIN pending_results p ON p.id=c.id AND p.device=c.device WHERE c.device=?', (self.device,)).fetchall()
                    for (result,) in rows:
                        await self.send(socket, 'COMMAND_RESULT', json.loads(result))
                    await asyncio.sleep(2)
            retry = asyncio.create_task(resend())
            try:
                done, _ = await asyncio.wait([reporter, receiver, retry], return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
            finally:
                reporter.cancel()
                receiver.cancel()
                retry.cancel()
                await asyncio.gather(reporter, receiver, retry, return_exceptions=True)

    async def run(self):
        delay = 1
        while True:
            try:
                await self.session()
                delay = 1
            except (OSError, TimeoutError) as exc:
                LOG.warning("Socket unavailable (%s)", type(exc).__name__)
            except WebSocketException as exc:
                LOG.warning("Socket disconnected/rejected (%s)", type(exc).__name__)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["state", "enqueue", "flush", "status"])
    parser.add_argument("--image")
    parser.add_argument("--label")
    parser.add_argument("--model", choices=["OBJECT", "HAZARD"])
    parser.add_argument("--event-id")
    parser.add_argument("--store", default=str(Path(__file__).parent / ".runtime" / "transport.sqlite3"))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    bridge = Bridge(os.environ["ROBOT_DEVICE_ID"], os.environ["ROBOT_DEVICE_TOKEN"],
        os.environ.get("ROBOT_HTTP_URL", "http://localhost:8080"),
        os.environ.get("ROBOT_WS_URL", "ws://localhost:8080/ws/devices"), args.store)
    try:
        if args.mode == "state":
            asyncio.run(bridge.run())
        elif args.mode == "enqueue":
            if not all([args.image, args.label, args.model]):
                parser.error("enqueue requires --image, --label, --model")
            print(bridge.enqueue_detection(Path(args.image).read_bytes(), args.label, args.model, args.event_id))
        elif args.mode == "flush":
            bridge.flush_once()
        else:
            for row in bridge.db.execute("SELECT id,status,response FROM events WHERE device=?", (bridge.device,)):
                print(*row)
    finally:
        bridge.close()


if __name__ == "__main__":
    main()

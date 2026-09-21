"""Local receiver only. Shows HTTP/WS receipt; does not emulate Spring or its DB."""
import asyncio
import hashlib
import json
import os
import threading
from datetime import datetime, timedelta, timezone
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID, uuid4
from websockets.asyncio.server import serve

TOKEN = os.environ.get('ROBOT_DEVICE_TOKEN', 'local-test-token-0123456789abcdef')
DEVICE = os.environ.get('ROBOT_DEVICE_ID', 'robot-test')
EVENTS = {}
LOCK = threading.Lock()
# Stands in for the frontend -> backend -> device command path. Spring issues
# PAUSE with a 10s expiry; nothing else reaches the device.
PENDING = []
COMMAND_TTL = 10


def authorized(headers):
    return headers.get('Authorization') == 'Bearer ' + TOKEN and headers.get('X-Device-Id') == DEVICE


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path == '/mock/command':
            size = int(self.headers.get('Content-Length', '0'))
            body = json.loads(self.rfile.read(size) or b'{}') if size else {}
            command = body.get('command', 'PAUSE')
            entry = {'commandId': str(uuid4()), 'command': command,
                     'expiresAt': (datetime.now(timezone.utc) + timedelta(seconds=COMMAND_TTL)).isoformat()}
            with LOCK:
                PENDING.append(entry)
            print(f"WS COMMAND QUEUED commandId={entry['commandId']} command={command}", flush=True)
            return self.reply(200, entry)
        if self.path != '/api/v1/hardware/detections':
            return self.reply(404, {'error': 'path'})
        if not authorized(self.headers):
            return self.reply(401, {'error': 'authentication'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 6 * 1024 * 1024:
                return self.reply(413, {'error': 'size'})
            message = BytesParser(policy=default).parsebytes(
                ('Content-Type: ' + self.headers.get('Content-Type', '') + '\r\n\r\n').encode() + self.rfile.read(size))
            fields = {p.get_param('name', header='content-disposition'): p.get_payload(decode=True) for p in message.iter_parts()}
            event = str(UUID(fields['eventId'].decode()))
            label = fields['objectLabel'].decode('utf-8')
            model = fields['modelType'].decode()
            picture = fields['image']
            if model not in {'HAZARD', 'OBJECT'} or not label.strip() or len(label) > 100:
                raise ValueError()
            if not 0 < len(picture) <= 5 * 1024 * 1024 or not picture.startswith((b'\xff\xd8\xff', b'\x89PNG\r\n\x1a\n')):
                raise ValueError()
            fingerprint = (label, model, hashlib.sha256(picture).hexdigest())
            with LOCK:
                if event in EVENTS and EVENTS[event] != fingerprint:
                    return self.reply(409, {'error': 'event conflict'})
                EVENTS[event] = fingerprint
            print(f'HTTP RECEIVED eventId={event} model={model} label={label} bytes={len(picture)}', flush=True)
            self.reply(200, {'eventId': event, 'hazardId': None})
        except (ValueError, KeyError, TypeError):
            self.reply(400, {'error': 'invalid multipart'})


async def push_commands(socket):
    """Deliver what /mock/command queued, the way Spring pushes a COMMAND."""
    while True:
        with LOCK:
            entry = PENDING.pop(0) if PENDING else None
        if entry is not None:
            await socket.send(json.dumps({'type': 'COMMAND', 'messageId': str(uuid4()),
                                          'deviceId': DEVICE, 'sentAt': datetime.now(timezone.utc).isoformat(),
                                          'payload': entry}))
            print(f"WS COMMAND SENT commandId={entry['commandId']} command={entry['command']}", flush=True)
        await asyncio.sleep(0.1)


async def socket_handler(socket):
    if socket.request.path != '/ws/devices' or not authorized(socket.request.headers):
        await socket.close(1008, 'Authentication/path rejected')
        return
    pusher = asyncio.create_task(push_commands(socket))
    try:
        await receive_messages(socket)
    finally:
        pusher.cancel()
        await asyncio.gather(pusher, return_exceptions=True)


async def acknowledge(socket, message):
    """Mirrors DeviceMessageService: SUCCEEDED is only valid with operationState PAUSED."""
    payload = message['payload']
    UUID(payload['commandId'])
    if message['type'] == 'COMMAND_ACK':
        if payload['status'] != 'DELIVERED':
            raise ValueError()
        print(f"WS COMMAND_ACK commandId={payload['commandId']} status=DELIVERED", flush=True)
    else:
        if payload['status'] not in {'SUCCEEDED', 'FAILED'}:
            raise ValueError()
        if payload['status'] == 'SUCCEEDED' and payload.get('operationState') != 'PAUSED':
            raise ValueError()
        datetime.fromisoformat(payload['completedAt'])
        print(f"WS COMMAND_RESULT commandId={payload['commandId']} status={payload['status']} "
              f"operation={payload.get('operationState')} error={payload.get('errorCode')}", flush=True)
    await socket.send(json.dumps({'type': 'RECEIPT', 'messageId': message['messageId'], 'accepted': True}))


async def receive_messages(socket):
    async for raw in socket:
        try:
            message = json.loads(raw)
            UUID(message['messageId'])
            if message['deviceId'] != DEVICE or datetime.fromisoformat(message['sentAt']).tzinfo is None:
                raise ValueError()
            if message['type'] in {'COMMAND_ACK', 'COMMAND_RESULT'}:
                await acknowledge(socket, message)
                continue
            if message['type'] != 'ROBOT_STATE':
                raise ValueError()
            state = message['payload']
            if state['operationState'] not in {'RUNNING', 'PAUSED', 'RELOCATING', 'UNKNOWN'}:
                raise ValueError()
            if state['movementState'] not in {'FORWARD', 'TURNING', 'BACKWARD', 'STOPPED', 'UNKNOWN'}:
                raise ValueError()
            if datetime.fromisoformat(state['sampledAt']).tzinfo is None:
                raise ValueError()
            print(f"WS RECEIVED device={DEVICE} operation={state['operationState']} movement={state['movementState']}", flush=True)
            await socket.send(json.dumps({'type': 'RECEIPT', 'messageId': message['messageId'], 'accepted': True}))
        except (ValueError, KeyError, TypeError):
            await socket.send(json.dumps({'type': 'ERROR', 'code': 'INVALID_MESSAGE'}))


async def main():
    server = ThreadingHTTPServer(('127.0.0.1', 18080), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        async with serve(socket_handler, '127.0.0.1', 18081):
            print('LOCAL MOCK ONLY: HTTP 127.0.0.1:18080 / WS 127.0.0.1:18081; Ctrl+C to stop', flush=True)
            await asyncio.Future()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass

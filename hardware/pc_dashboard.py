#!/usr/bin/env python3
"""Local browser dashboard: Pi MJPEG preview plus existing TCP motor control.

Core transport uses the standard library; optional AI runs in a child process.
"""
import argparse
import json
import secrets
import threading
import time
import urllib.request
import webbrowser
from uploader import start_state_reporter, start_uploader
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from pc_client import RobotClient
from detection.service import DetectionService
from detection.http_api import handle_get

FRAME_LIMIT = 2 * 1024 * 1024
VIDEO_TTL = 0.7
INPUT_TTL = 0.45
MOTOR_PERIOD = 0.12
# Reversed wiring: translate only at the motor transport boundary.
MOTOR_COMMAND_MAP = {'F': 'B', 'B': 'F', 'L': 'R', 'R': 'L', 'S': 'S'}
# Reported from the motor's acknowledgement, never from what a browser asked for.
MOVEMENT_STATES = {'F': 'FORWARD', 'B': 'BACKWARD', 'L': 'TURNING', 'R': 'TURNING', 'S': 'STOPPED'}
PAUSE_CONFIRM_SEC = 3.0


class CameraFeed:
    def __init__(self):
        self.lock = threading.Lock()
        self.jpeg = None
        self.sequence = 0
        self.recent = deque(maxlen=20)
        self.error = 'Waiting for camera'

    def publish(self, jpeg):
        received_at = time.monotonic()
        if not jpeg.startswith(b'\xff\xd8') or not jpeg.endswith(b'\xff\xd9'):
            raise ValueError('Invalid JPEG')
        with self.lock:
            self.jpeg = jpeg
            self.sequence += 1
            self.recent.append((self.sequence, received_at))
            self.error = None

    def snapshot(self):
        with self.lock:
            return self.jpeg, self.sequence, (self.recent[-1][1] if self.recent else 0), self.error

    def frame_time(self, sequence):
        with self.lock:
            if self.error:
                return 0
            return next((stamp for seq, stamp in self.recent if seq == sequence), 0)

    def fail(self, message):
        with self.lock:
            self.error = message


def read_mjpeg(response, feed, stop):
    while not stop.is_set():
        line = response.readline(256)
        if not line:
            raise ConnectionError('Camera stream closed')
        if line in (b'\r\n', b'\n'):
            continue
        if line.strip() != b'--FRAME':
            raise ValueError('Unexpected MJPEG boundary')
        length = None
        for _ in range(12):
            header = response.readline(256)
            if header in (b'\r\n', b'\n'):
                break
            if not header or len(header) >= 256:
                raise ValueError('Invalid MJPEG header')
            if header.lower().startswith(b'content-length:'):
                length = int(header.split(b':', 1)[1])
        else:
            raise ValueError('Too many MJPEG headers')
        if length is None or not 4 <= length <= FRAME_LIMIT:
            raise ValueError('Invalid JPEG size')
        jpeg = response.read(length)
        if len(jpeg) != length:
            raise ConnectionError('Incomplete JPEG')
        feed.publish(jpeg)


def camera_worker(args, feed, stop):
    url = f'http://{args.host}:{args.camera_port}/stream.mjpg'
    # Direct LAN connection, independent of system HTTP proxy settings.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while not stop.is_set():
        try:
            request = urllib.request.Request(url)
            with opener.open(request, timeout=1.0) as response:
                read_mjpeg(response, feed, stop)
        except Exception as exc:
            feed.fail(str(exc))
        stop.wait(1.0)


class Controls:
    def __init__(self, feed):
        self.lock = threading.Lock()
        self.feed = feed
        self.ready = False
        self.owner = None
        self.sequence = 0
        self.desired = 'S'
        self.updated = 0
        self.frame_stamp = 0
        self.ack = 'S'
        self.error = 'Connecting to motor receiver'
        self.last_stop = None
        self.last_stop_at = None
        self.ack_ms = None
        # Set only by a backend PAUSE the motor confirmed; driving again clears it.
        self.paused = False

    def revoke(self, reason):
        if self.owner is not None or reason.startswith('Motor connection failed:'):
            self.last_stop, self.last_stop_at = reason, time.time()
        self.owner = None
        self.desired = 'S'
        self.error = reason

    def claim(self, owner, frame):
        now = time.monotonic()
        stamp = self.feed.frame_time(frame)
        with self.lock:
            if not self.ready:
                raise ValueError('Motor connection is not ready')
            if not stamp or now - stamp > VIDEO_TTL:
                raise ValueError('Wait for fresh video')
            if self.owner is not None and now-self.updated <= INPUT_TTL:
                raise ValueError('Controls are already enabled in a browser tab')
            self.owner, self.sequence, self.desired = owner, 0, 'S'
            self.updated, self.frame_stamp, self.error = now, stamp, None

    def update(self, owner, seq, command, frame):
        stamp = self.feed.frame_time(frame)
        now = time.monotonic()
        with self.lock:
            if not self.ready or self.owner != owner:
                raise ValueError('Controls disabled. Click Enable controls')
            if now-self.updated > INPUT_TTL:
                self.revoke('Browser heartbeat expired')
                raise ValueError(self.error)
            if type(seq) is not int or seq != self.sequence+1 or command not in ('F','B','L','R','S'):
                self.revoke('Invalid command sequence')
                raise ValueError(self.error)
            if not stamp or now-stamp > VIDEO_TTL:
                self.revoke('Video frame expired')
                raise ValueError(self.error)
            self.sequence, self.desired = seq, command
            self.updated, self.frame_stamp = now, stamp
            if command != 'S':
                self.paused = False

    def stop_owner(self, owner, reason='Controls disabled'):
        with self.lock:
            if self.owner == owner:
                self.revoke(reason)

    def command(self):
        now = time.monotonic()
        _, _, camera_stamp, camera_error = self.feed.snapshot()
        with self.lock:
            if self.owner:
                if camera_error:
                    self.revoke('Camera stream error: ' + camera_error)
                elif now-self.updated > INPUT_TTL:
                    self.revoke(f'Browser heartbeat expired: {now-self.updated:.3f}s > {INPUT_TTL}s')
                elif now-self.frame_stamp > VIDEO_TTL:
                    self.revoke(f'Browser video frame expired: {now-self.frame_stamp:.3f}s > {VIDEO_TTL}s')
                elif now-camera_stamp > VIDEO_TTL:
                    self.revoke(f'Camera receipt expired: {now-camera_stamp:.3f}s > {VIDEO_TTL}s')
            return self.desired if self.owner else 'S'

    def pause(self, reason):
        """Backend PAUSE: drop browser control and hold stop. False when no motor exists."""
        with self.lock:
            if not self.ready:
                return False
            self.revoke(reason)
            self.paused = True
            return True

    def robot_state(self):
        """What the motor actually confirmed. No motor means UNKNOWN, not STOPPED.

        operationState stays UNKNOWN until a backend PAUSE is confirmed: this
        program tracks motor commands, not a cleaning operation.
        """
        with self.lock:
            if not self.ready:
                return dict(operationState='UNKNOWN', movementState='UNKNOWN')
            return dict(operationState='PAUSED' if self.paused and self.ack == 'S' else 'UNKNOWN',
                        movementState=MOVEMENT_STATES.get(self.ack, 'UNKNOWN'))

    def state(self):
        with self.lock:
            return dict(ready=self.ready, enabled=self.owner is not None, ack=self.ack, error=self.error,
                        last_stop=self.last_stop, last_stop_at=self.last_stop_at, ack_ms=self.ack_ms,
                        heartbeat_age=round(time.monotonic()-self.updated,3) if self.owner else None)


def backend_commands(controls, stop):
    """Frontend -> backend -> socket -> here. PAUSE is the only command the backend sends."""
    def execute(command, command_id):
        if command != 'PAUSE':
            return dict(status='FAILED', errorCode='UNSUPPORTED_COMMAND')
        if not controls.pause('Backend PAUSE command ' + command_id):
            return dict(status='FAILED', errorCode='HARDWARE_NOT_CONNECTED')
        # Report the motor's acknowledgement, not the fact that a stop was requested.
        deadline = time.monotonic() + PAUSE_CONFIRM_SEC
        while time.monotonic() < deadline and not stop.is_set():
            state = controls.state()
            if not state['ready']:
                return dict(status='FAILED', errorCode='HARDWARE_NOT_CONNECTED')
            if state['ack'] == 'S':
                return dict(status='SUCCEEDED', operationState='PAUSED')
            time.sleep(0.05)
        return dict(status='FAILED', errorCode='STOP_NOT_CONFIRMED')
    return execute


def motor_worker(args, controls, stop):
    robot = None
    try:
        robot = RobotClient(args.host, None, args.control_port)
        with controls.lock:
            controls.ready, controls.error = True, None
        while not stop.is_set():
            cycle_started = time.monotonic()
            command = controls.command()
            robot.drive(MOTOR_COMMAND_MAP[command])
            with controls.lock:
                controls.ack = command
                controls.ack_ms = round((time.monotonic()-cycle_started)*1000,1)
            # ACK wait is part of the period, not an extra delay before it.
            stop.wait(max(0, MOTOR_PERIOD-(time.monotonic()-cycle_started)))
    except Exception as exc:
        with controls.lock:
            controls.revoke('Motor connection failed: ' + type(exc).__name__ + ': ' + str(exc))
            controls.ready = False
    finally:
        if robot is not None:
            robot.close()


class Dashboard(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, port, feed, controls, detection=None):
        self.detection = detection
        self.feed, self.controls = feed, controls
        self.secret = secrets.token_urlsafe(24)
        self.page = Path(__file__).with_name('dashboard.html').read_text(encoding='utf-8').replace('__SECRET__', self.secret).encode()
        super().__init__(('127.0.0.1', port), Handler)


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(2)

    def log_message(self, *_args):
        pass

    def reply(self, status, content_type, data, headers=None):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Frame-Options', 'DENY')
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            if handle_get(self):
                return
            if self.path == '/':
                self.reply(200, 'text/html; charset=utf-8', self.server.page)
            elif self.path.startswith('/frame?'):
                jpeg, seq, stamp, error = self.server.feed.snapshot()
                if jpeg is None or error or time.monotonic()-stamp > VIDEO_TTL:
                    self.reply(503, 'text/plain', b'Waiting for fresh video')
                else:
                    self.reply(200, 'image/jpeg', jpeg, {'X-Frame-Sequence':seq})
            elif self.path == '/state':
                state = self.server.controls.state()
                state['camera_error'] = self.server.feed.snapshot()[3]
                self.reply(200, 'application/json', json.dumps(state).encode())
            else:
                self.reply(404, 'text/plain', b'Not found')
        except OSError:
            pass

    def do_POST(self):
        try:
            origin = self.headers.get('Origin')
            expected = 'http://127.0.0.1:' + str(self.server.server_port)
            if origin != expected:
                self.reply(403, 'text/plain', b'Invalid origin'); return
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 2048:
                raise ValueError('Invalid request size')
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict) or not isinstance(data.get('secret'), str):
                raise ValueError('Invalid request')
            if not secrets.compare_digest(data['secret'], self.server.secret):
                self.reply(403, 'text/plain', b'Invalid session'); return
            owner = data.get('owner')
            if not isinstance(owner, str) or not 8 <= len(owner) <= 128:
                raise ValueError('Invalid owner')
            if self.path == '/detections/mode':
                if self.server.detection is None:
                    raise ValueError('Detection unavailable')
                self.server.detection.select_mode(data.get('mode'))
            elif self.path == '/arm':
                self.server.controls.claim(owner, data.get('frame'))
            elif self.path == '/control':
                self.server.controls.update(owner, data.get('seq'), data.get('command'), data.get('frame'))
            elif self.path == '/stop':
                reason = data.get('reason', 'Controls disabled')
                if not isinstance(reason, str) or len(reason) > 200:
                    raise ValueError('Invalid stop reason')
                self.server.controls.stop_owner(owner, reason)
            else:
                raise ValueError('Unknown operation')
            self.reply(200, 'application/json', b'{"ok":true}')
        except (ValueError, TypeError) as exc:
            try: self.reply(400, 'application/json', json.dumps({'error':str(exc)}).encode())
            except OSError: pass
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--motor', action='store_true', help='Enable Arduino control; default is camera only')
    parser.add_argument('--control-port', type=int, default=8765)
    parser.add_argument('--camera-port', type=int, default=8000)
    parser.add_argument('--port', type=int, default=8080, help='PC local dashboard port')
    parser.add_argument('--no-browser', action='store_true')
    parser.add_argument('--no-detection', action='store_true', help='Run video and controls only')
    parser.add_argument('--detection-mode', choices=('object', 'hazard'), default='object')
    args = parser.parse_args()
    # Pi rotates the camera globally before streaming. Keep those exact bytes so
    # dashboard, object/ArUco detection and uploads cannot apply a second flip.
    feed, stop = CameraFeed(), threading.Event()
    controls = Controls(feed)
    detection = DetectionService(feed, enabled=not args.no_detection, mode=args.detection_mode)
    server = Dashboard(args.port, feed, controls, detection)
    workers = [threading.Thread(target=camera_worker, args=(args,feed,stop), daemon=True)]
    if args.motor:
        workers.append(threading.Thread(target=motor_worker, args=(args,controls,stop), daemon=True))
    else:
        controls.error = 'Camera only: Arduino not connected'
    uploader = start_uploader(stop)
    reporter = start_state_reporter(stop, controls.robot_state, backend_commands(controls, stop))
    for worker in workers: worker.start()
    detection.start()
    url = f'http://127.0.0.1:{server.server_port}/'
    print('Dashboard: ' + url, flush=True)
    print('Keep this terminal open. Use controls in the browser. Ctrl+C to exit.', flush=True)
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        print('Stopping dashboard...', flush=True)
    finally:
        with controls.lock: controls.revoke('Dashboard exiting')
        stop.set()
        for worker in workers: worker.join(timeout=2)
        detection.close()
        server.server_close()


if __name__ == '__main__':
    main()

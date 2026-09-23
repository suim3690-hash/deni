"""Frontend-free check of the whole command path. No frontend, no Spring, no DB, no robot.

Runs mock_backend.py, the real Bridge socket and the real CareController against a fake
motor and a scripted camera, then issues backend commands the way the frontend would.
Nothing here proves the robot moves; it proves which commands are accepted, what the
device reports back, and that the mock refuses what Spring would refuse.

    .\\.venv\\Scripts\\python.exe check_without_frontend.py
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from uuid import uuid4

HERE = Path(__file__).parent
os.environ.setdefault('ROBOT_DEVICE_ID', 'robot-test')
os.environ.setdefault('ROBOT_DEVICE_TOKEN', 'local-test-token-0123456789abcdef')
os.environ.setdefault('ROBOT_HTTP_URL', 'http://127.0.0.1:18080')
os.environ.setdefault('ROBOT_WS_URL', 'ws://127.0.0.1:18081/ws/devices')
sys.path.insert(0, str(HERE))

from care_controller import Settings  # noqa: E402
from care_runtime import Runtime  # noqa: E402
from uploader import start_state_reporter  # noqa: E402

HAZARD = str(uuid4())
MOCK = 'http://127.0.0.1:18080/mock/command'


class FakeMotor:
    """Stands in for a connected Pi: accepts a command and acknowledges it at once."""

    def __init__(self):
        self.lock = threading.Lock()
        self.ack, self.ack_at, self.sent = 'S', time.monotonic(), []

    def submit(self, command, until, generation=None):
        with self.lock:
            self.ack, self.ack_at = command, time.monotonic()
            self.sent.append(command)

    def observation(self):
        with self.lock:
            return dict(ready=True, error=None, ack=self.ack, generation=1, acknowledged_at=self.ack_at)


class ScriptedCamera:
    """Publishes the detection results a real inference worker would publish."""

    def __init__(self):
        self.lock = threading.Lock()
        self.labels, self.fill, self.sequence, self.processing = [], 0.05, 0, False
        self.suppressed = set()

    def set_processing(self, enabled):
        with self.lock:
            self.processing = enabled

    def set_suppressed_alert_labels(self, labels):
        with self.lock:
            self.suppressed = set(labels)

    def show(self, labels, fill=0.05):
        with self.lock:
            self.labels, self.fill = list(labels), fill

    def state(self):
        with self.lock:
            self.sequence += 1
            hazards = [dict(label=name, bbox=[300, 200, 340, 240]) for name in self.labels] if self.processing else []
            return dict(status='ok', frame_stamp=time.monotonic(), sequence=self.sequence, hazards=hazards,
                        markers=[dict(id=0, fill=self.fill, bearing=0.0, skew=0.1, centre=[320, 220])],
                        frame_width=640, frame_height=480)


def issue(command, parameters=None):
    body = {'command': command}
    if parameters:
        body['parameters'] = parameters
    request = urllib.request.Request(MOCK, method='POST', data=json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def main():
    log, failures, passes = [], [], []
    child = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1')
    backend = subprocess.Popen([sys.executable, 'mock_backend.py'], cwd=HERE, env=child, text=True,
                               encoding='utf-8', errors='replace', bufsize=1,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def drain():
        for line in backend.stdout:
            log.append(line.rstrip())
    threading.Thread(target=drain, daemon=True).start()

    def wait_for(predicate, label, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return True
            time.sleep(0.1)
        failures.append('timed out waiting for ' + label)
        return False

    def check(name, condition):
        (passes if condition else failures).append(name)
        print(('PASS  ' if condition else 'FAIL  ') + name, flush=True)

    def finished(command_id):
        return any(command_id in line and 'status=SUCCEEDED' in line for line in log)

    wait_for(lambda: any('LOCAL MOCK ONLY' in line for line in log), 'mock backend start')
    stop = threading.Event()
    motor, camera = FakeMotor(), ScriptedCamera()
    runtime = Runtime(motor, camera, stop, Settings())
    reporter = None
    try:
        threading.Thread(target=runtime.run, daemon=True).start()
        reporter = start_state_reporter(stop, runtime.state, runtime.command)
        wait_for(lambda: any('WS RECEIVED' in line for line in log), 'first state report')
        check('boot is powered off with detection and motor stopped',
              runtime.state()['taskState'] == 'OFF' and not camera.processing
              and motor.observation()['ack'] == 'S')

        power_on = issue('POWER_ON')
        wait_for(lambda: runtime.state()['taskState'] == 'RUNNING', 'POWER_ON')
        check('POWER_ON starts detection', camera.processing)
        # Driving only starts on the first observation newer than the command, so wait for it.
        check('POWER_ON starts forward driving', wait_for(lambda: 'F' in motor.sent, 'forward driving'))
        wait_for(lambda: finished(power_on['commandId']), 'POWER_ON result')
        check('POWER_ON is confirmed independently of detection warm-up',
              any(power_on['commandId'] in line and 'status=SUCCEEDED' in line for line in log))

        camera.show(['coin'])
        wait_for(lambda: runtime.state()['taskState'] == 'HAZARD_PAUSED', 'hazard stop')
        check('a swallow hazard stops driving by itself', motor.observation()['ack'] == 'S')

        recheck = issue('RECHECK_HAZARD', {'hazardId': HAZARD, 'objectLabel': '동전'})
        wait_for(lambda: runtime.state()['taskState'] == 'RECHECKING', 'recheck start')
        time.sleep(Settings().removal_absence_seconds + 2)
        check('recheck stays open while the object is still visible',
              runtime.state()['taskState'] == 'RECHECKING' and not finished(recheck['commandId']))
        camera.show([])
        wait_for(lambda: finished(recheck['commandId']), 'recheck result')
        check('recheck completes only after the absence window', finished(recheck['commandId']))

        camera.show(['coin'])
        wait_for(lambda: runtime.state()['taskState'] == 'HAZARD_PAUSED', 'hazard stop before relocation')
        relocate = issue('RELOCATE', {'hazardId': HAZARD, 'objectLabel': '동전'})
        wait_for(lambda: runtime.state()['taskState'] == 'CAPTURING', 'target capture start')
        check('relocation suppresses only the target from backend alert uploads', camera.suppressed == {'coin'})
        camera.show([])
        wait_for(lambda: runtime.state()['taskState'] == 'PUSHING_TO_MARKER', 'marker-guided relocation start')
        check('relocation keeps moving after the captured object leaves view', wait_for(lambda: 'F' in motor.sent[-30:], 'push'))
        # Reaching the marker size limit ends the push; the object is then out of the way.
        camera.show([], fill=Settings().marker_stop_fill + 0.01)
        wait_for(lambda: runtime.state()['taskState'] == 'BACKING', 'backing')
        camera.show(['coin'], fill=Settings().marker_stop_fill - 0.01)
        wait_for(lambda: runtime.state()['taskState'] == 'VERIFYING_DROP', 'drop verification')
        wait_for(lambda: runtime.state()['taskState'] == 'TURNING_AROUND', 'turnaround')
        camera.show([])
        wait_for(lambda: finished(relocate['commandId']), 'relocation result')
        check('relocation clears target alert suppression after turning', camera.suppressed == set())
        check('relocation reverses then turns on the configured timers',
              'B' in motor.sent and 'R' in motor.sent)
        check('relocation reports completion after the timed moves', finished(relocate['commandId']))

        reported = sum('WS RECEIVED' in line for line in log)
        power_off = issue('POWER_OFF')
        wait_for(lambda: runtime.state()['taskState'] == 'OFF', 'POWER_OFF')
        # The task state flips as the command is accepted; the motor stops on the next control tick.
        check('POWER_OFF stops detection and the motor',
              not camera.processing and wait_for(lambda: motor.observation()['ack'] == 'S', 'motor stop'))
        wait_for(lambda: sum('WS RECEIVED' in line for line in log) > reported, 'state report while off')
        check('state reporting keeps running while powered off',
              sum('WS RECEIVED' in line for line in log) > reported)
        wait_for(lambda: finished(power_off['commandId']), 'POWER_OFF result')
        check('POWER_OFF is reported as SUCCEEDED/PAUSED',
              any(power_off['commandId'] in line and 'operation=PAUSED' in line for line in log))

        try:
            issue('RECHECK_HAZARD')
            check('the mock refuses a hazard command without parameters', False)
        except urllib.error.HTTPError as error:
            check('the mock refuses a hazard command without parameters', error.code == 400)
        check('the mock accepted every result the device sent',
              not any('INVALID_MESSAGE' in line or 'not accepted' in line for line in log))
    finally:
        stop.set()
        time.sleep(0.5)
        if reporter is not None:
            reporter.join(timeout=3)
        backend.terminate()
        try:
            backend.wait(timeout=5)
        except subprocess.TimeoutExpired:
            backend.kill()

    print()
    print(f'{len(passes)} passed, {len(failures)} failed')
    for name in failures:
        print('  FAILED:', name)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())

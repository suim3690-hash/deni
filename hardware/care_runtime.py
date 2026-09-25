"""Integrated PC runtime: frontend API -> backend socket -> task controller -> Pi.

Starts powered off. Only backend POWER_ON starts detection and autonomous driving.
Pi runs pi_robot_server.py; never run a second PC motor owner alongside this process.
"""
import argparse
import json
import logging
from logging.handlers import RotatingFileHandler
import threading
import time
import urllib.request
from pathlib import Path

from care_controller import CareController, Settings, LABELS
from detection.service import DetectionService
from motor_output import MotorOutput, DRIVE_REVERSED
from pc_dashboard import CameraFeed, read_mjpeg
from uploader import configured, start_state_reporter, start_uploader

LOG = logging.getLogger('care')
MOVEMENT = {'F': 'FORWARD', 'B': 'BACKWARD', 'L': 'TURNING', 'R': 'TURNING', 'S': 'STOPPED'}
# The drop-off marker is far away in these phases; floor objects are close in all others.
MARKER_PHASES = {'SEEKING_MARKER', 'PUSHING_TO_MARKER'}


class FocusSwitch:
    """Pi autofocus range: far while looking for the marker, near otherwise.

    Runs on its own thread so a slow or old Pi server never delays motor control.
    """

    def __init__(self, url, near, far, stop, post=None):
        self.url, self.near, self.far, self.stop = url, near, far, stop
        self.wanted, self.applied = near, None
        self.wake = threading.Event()
        self.post = post or self._post
        self.warned = False

    def want(self, far):
        wanted = self.far if far else self.near
        if wanted != self.wanted:
            self.wanted = wanted
            self.wake.set()

    def _post(self, name):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request(self.url + '?range=' + name, data=b'', method='POST')
        with opener.open(request, timeout=2) as response:
            response.read()

    def sync_once(self):
        """Apply the wanted range; False means retry later."""
        wanted = self.wanted
        if wanted == self.applied:
            return True
        try:
            self.post(wanted)
        except Exception as exc:
            if not self.warned:
                LOG.warning('Camera focus switch to %s failed (%s); Pi server may predate POST /focus',
                            wanted, type(exc).__name__)
                self.warned = True
            return False
        self.applied, self.warned = wanted, False
        LOG.info('Camera focus range: %s', wanted)
        return True

    def run(self):
        while not self.stop.is_set():
            self.wake.clear()
            if not self.sync_once():
                self.stop.wait(2)
                continue
            self.wake.wait(1)


class Runtime:
    def __init__(self, motor, detector, stop, settings, snapshot_path=None, focus=None):
        self.motor, self.detector, self.stop = motor, detector, stop
        self.focus = focus
        self.controller = CareController(settings)
        self.lock = threading.RLock()
        self.snapshot_path = snapshot_path
        # Monotonic time of the last backend message; None until the socket first answers.
        self.backend_contact = None
        self.health_lock = threading.Lock()
        self.control_started = False
        self.control_tick = 0.0
        self.control_fault = None
        # A late loop only makes reports UNKNOWN; the motor lease (0.15 s) already stops it.
        # Latch a fault, which needs a restart, only after a real stall or a crash:
        # a short disk or log hiccup must not end a demo.
        self.control_timeout = .5
        self.control_fault_after = 3.0

    def fail_control(self, code):
        with self.health_lock:
            if self.control_fault is None:
                LOG.error('Control fault latched: %s; restart required', code)
                self.control_fault = code
        # Never wait on the controller lock before withdrawing the motor lease.
        if self.motor is not None:
            self.motor.submit('S', 0)
        if self.lock.acquire(blocking=False):
            try:
                controller = self.controller
                controller.observed_state = 'UNKNOWN'
                if controller.action:
                    controller.blocked.add(controller.action[2]['label'])
                    controller._complete_action('FAILED', code)
                if controller.control:
                    controller.results[controller.control[1]] = dict(
                        status='FAILED', operationState='UNKNOWN', errorCode=code)
                    controller.control = None
                controller.phase = 'HAZARD_PAUSED' if controller.blocked else 'PAUSED'
                controller.reason = code
                controller.last_output = 'S'
            finally:
                self.lock.release()

    def control_age(self):
        with self.health_lock:
            return time.monotonic()-self.control_tick if self.control_started else None

    def control_healthy(self):
        age = self.control_age()
        return self.control_fault is None and age is not None and age <= self.control_timeout

    def check_stall(self):
        age = self.control_age()
        if age is not None and age > self.control_fault_after:
            self.fail_control('CONTROL_LOOP_STALE')

    def supervise(self):
        while not self.stop.wait(.1):
            self.check_stall()

    def backend_contacted(self):
        self.backend_contact = time.monotonic()

    def backend_ok(self, now):
        contact = self.backend_contact
        return contact is not None and now-contact <= self.controller.settings.backend_timeout_seconds

    def _sync_alert_suppression(self):
        action = self.controller.action
        labels = {action[2]['label']} if action and action[0] == 'RELOCATE' else set()
        self.detector.set_suppressed_alert_labels(labels)
        if hasattr(self.detector, 'set_relocated_labels'):
            self.detector.set_relocated_labels(self.controller.relocated_labels)

    def state(self):
        # Never pair a fresh sampledAt with the last state of a loop that is not running.
        unknown = dict(operationState='UNKNOWN', powerEnabled=None,
                       taskState='CONTROL_FAULT' if self.control_fault else
                       'CONTROL_STALE' if self.control_started else 'CONTROL_STARTING',
                       movementState='UNKNOWN')
        if not self.control_healthy():
            return unknown
        motor = self.motor.observation()
        if not self.lock.acquire(timeout=.1):
            return unknown
        try:
            if not self.control_healthy():
                return unknown
            return dict(operationState=self.controller.observed_state,
                        powerEnabled=self.controller.powered, taskState=self.controller.phase,
                        movementState=MOVEMENT.get(motor['ack'], 'UNKNOWN') if motor['ready'] else 'UNKNOWN')
        finally:
            self.lock.release()

    def command(self, command, identity, parameters=None):
        parameters = parameters or {}
        if command == 'RECOVER_COMMAND':
            return self.recover_command(identity, parameters)
        def fault_result(code=None):
            result = dict(status='FAILED', operationState='UNKNOWN',
                          errorCode=self.control_fault or code or 'CONTROL_LOOP_STALE')
            if parameters.get('hazardId'): result['hazardId'] = parameters['hazardId']
            return result
        if self.control_fault:
            return fault_result()
        reset_label = LABELS.get(parameters.get('objectLabel')) if command == 'RECHECK_HAZARD' else None
        if not self.lock.acquire(timeout=self.control_timeout):
            # Nothing was started, so report failure; the supervisor latches a real stall.
            return fault_result('CONTROL_LOCK_TIMEOUT')
        try:
            if self.control_fault:
                return fault_result()
            before = self.controller.powered
            self.controller.request(command, identity, parameters, time.monotonic())
            self._sync_alert_suppression()
            if before != self.controller.powered:
                self.detector.set_processing(self.controller.powered)
        finally:
            self.lock.release()
        while not self.stop.wait(.05):
            if self.control_fault:
                return fault_result()
            if not self.lock.acquire(timeout=.1):
                continue
            try:
                result = self.controller.results.pop(identity, None)
                if result is not None:
                    if result.get('status') == 'SUCCEEDED' and reset_label:
                        self.detector.reset_alert_labels({reset_label})
                    LOG.info('Command %s %s: %s', command, identity, result['status'])
                    return result
            finally:
                self.lock.release()
        return dict(status='FAILED', operationState='UNKNOWN', errorCode='PROCESS_STOPPING')

    def recover_command(self, identity, parameters):
        """No movement or replay. Close an unknown request only after fresh stop evidence."""
        if not self.lock.acquire(timeout=.1):
            return None
        try:
            controller = self.controller
            # A finished result may not yet have reached the transport journal.
            result = controller.results.pop(identity, None)
            if result is not None:
                return result
            if controller.action and controller.action[1] != identity:
                return None
            if controller.control and controller.control[1] != identity:
                return None
            if controller.action:
                controller.blocked.add(controller.action[2]['label'])
                controller._complete_action('FAILED', 'RESULT_UNAVAILABLE')
                controller.results.pop(identity, None)
                controller.phase = 'HAZARD_PAUSED'
                controller.last_output = 'S'
                self.motor.submit('S', 0)
                return None
            if controller.control:
                controller.control = None
                controller.phase = 'HAZARD_PAUSED' if controller.blocked else 'PAUSED'
                controller.last_output = 'S'
                self.motor.submit('S', 0)
                return None
            motor = self.motor.observation()
            if (not self.control_healthy() or not motor.get('ready') or motor.get('ack') != 'S'
                    or time.monotonic()-motor.get('acknowledged_at', 0) > .25
                    or controller.phase not in {'OFF', 'PAUSED', 'HAZARD_PAUSED'}):
                return None
            result = dict(status='FAILED', operationState='PAUSED', errorCode='RESULT_UNAVAILABLE')
            if parameters.get('hazardId'): result['hazardId'] = parameters['hazardId']
            return result
        finally:
            self.lock.release()

    def run(self):
        with self.health_lock:
            self.control_started = True
            self.control_tick = time.monotonic()
        try:
            self._run_loop()
        except Exception:
            LOG.exception('Control loop stopped')
            self.fail_control('CONTROL_LOOP_FAILED')

    def _run_loop(self):
        previous = None
        next_snapshot = 0
        snapshot = self.snapshot_path
        while not self.stop.wait(.05):
            if self.control_fault:
                return
            observation = self.detector.state()
            motor = self.motor.observation()
            with self.lock:
                now = time.monotonic()
                command = self.controller.step(observation, motor, now, self.backend_ok(now))
                self._sync_alert_suppression()
                # A watchdog fault is latched: a delayed iteration must never re-arm motion.
                with self.health_lock:
                    if self.control_fault:
                        return
                    self.motor.submit(command, time.monotonic()+.15, motor['generation'])
                    self.control_tick = time.monotonic()
                if self.focus is not None:
                    self.focus.want(self.controller.phase in MARKER_PHASES)
                state =(self.controller.phase, self.controller.reason, motor['ready'])
                diagnostic = dict(task=self.controller.phase, reason=self.controller.reason,
                    powered=self.controller.powered, blocked=sorted(self.controller.blocked),
                    command=command, motor=motor, detectionStatus=observation.get('status'),
                    resultAge=observation.get('result_age'), blurScore=observation.get('blur_score'),
                    inferenceMs=observation.get('inference_ms'), sequence=observation.get('sequence'),
                    suppressedAlerts=observation.get('suppressed_alert_labels',[]),
                    modelErrors=observation.get('model_errors'), cameraUnavailable=observation.get('camera_unavailable',False),
                    objects=[dict(label=d.get('label'),confidence=d.get('confidence'),stable=d.get('stable')) for d in observation.get('hazards',[])],
                    markers=observation.get('markers',[]), recordedAt=time.time())
            if state != previous:
                LOG.info('Task=%s reason=%s motor=%s detection=%s age=%s blur=%s blocked=%s',
                         *state, diagnostic['detectionStatus'], diagnostic['resultAge'],
                         diagnostic['blurScore'], diagnostic['blocked'])
                previous = state
            if snapshot is not None and time.monotonic() >= next_snapshot:
                try:
                    snapshot.parent.mkdir(exist_ok=True)
                    temp = snapshot.with_suffix('.tmp')
                    temp.write_text(json.dumps(diagnostic,ensure_ascii=False),encoding='utf-8')
                    temp.replace(snapshot)
                except OSError:
                    LOG.warning('Could not write care_state.json')
                next_snapshot = time.monotonic()+1


def camera(args, feed, stop):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while not stop.is_set():
        try:
            request = urllib.request.Request(f'http://{args.host}:{args.camera_port}/stream.mjpg')
            with opener.open(request, timeout=2) as response:
                read_mjpeg(response, feed, stop)
        except Exception as exc:
            feed.fail(type(exc).__name__ + ': ' + str(exc))
            LOG.warning('Camera unavailable: %s', type(exc).__name__)
        stop.wait(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='172.30.1.10')
    parser.add_argument('--camera-port', type=int, default=8000)
    parser.add_argument('--control-port', type=int, default=8765)
    parser.add_argument('--config', type=Path, default=Path(__file__).with_name('care_config.json'))
    focus_ranges = ('macro', 'normal', 'full')
    parser.add_argument('--focus-range', choices=focus_ranges, default='macro',
                        help='Pi autofocus range for floor objects (default macro)')
    parser.add_argument('--marker-focus-range', choices=focus_ranges, default='normal',
                        help='Pi autofocus range while searching for or driving to the marker')
    args = parser.parse_args()
    settings = Settings(**json.loads(args.config.read_text(encoding='utf-8')))
    if not configured(): parser.error('Set ROBOT_HTTP_URL, ROBOT_WS_URL, ROBOT_DEVICE_ID and ROBOT_DEVICE_TOKEN')
    log = Path(__file__).parent/'.runtime'
    log.mkdir(exist_ok=True)
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', handlers=[
        logging.StreamHandler(), RotatingFileHandler(log/'care.log', maxBytes=2_000_000, backupCount=3, encoding='utf-8')])
    LOG.warning('Reverse %.2fs / turnaround %.2fs are temporary timing values, NOT a calibrated 180-degree angle.', settings.reverse_seconds, settings.turnaround_seconds)
    stop = threading.Event()
    # Pi already rotates every streamed frame by 180 degrees. Never rotate again.
    feed = CameraFeed()
    LOG.info('Using Pi global 180-degree stream unchanged for display, detection and uploads')
    detection = DetectionService(feed, mode='both', processing=False,
                                 safe_zone=(settings.marker_id, settings.drop_verify_radius_ratio))
    motor = MotorOutput(args.host, None, args.control_port, DRIVE_REVERSED)
    focus = FocusSwitch(f'http://{args.host}:{args.camera_port}/focus',
                        args.focus_range, args.marker_focus_range, stop)
    runtime = Runtime(motor,detection,stop,settings,log/'care_state.json',focus)
    threads = []
    try:
        # Validate backend settings before starting the motor owner.
        uploader = start_uploader(stop)
        if uploader: threads.append(uploader)
        detection.start()
        motor.thread.start()
        for target, arguments in ((camera,(args,feed,stop)),(runtime.run,()),(runtime.supervise,()),(focus.run,())):
            worker = threading.Thread(target=target,args=arguments,daemon=True)
            worker.start(); threads.append(worker)
        reporter = start_state_reporter(stop,runtime.state,runtime.command,runtime.backend_contacted)
        if reporter: threads.append(reporter)
        LOG.info('Ready, powered OFF. Waiting for backend POWER_ON. Ctrl+C stops this runtime.')
        while not stop.wait(1): pass
    except KeyboardInterrupt:
        LOG.info('Stopping runtime')
    finally:
        stop.set()
        motor.close()
        detection.close()
        for worker in threads: worker.join(timeout=3)


if __name__ == '__main__': main()

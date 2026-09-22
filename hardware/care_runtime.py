"""Integrated PC runtime: frontend API -> backend socket -> task controller -> Pi.

Starts powered off. Only backend POWER_ON starts detection and autonomous driving.
Pi runs pi_robot_server.py; never run a second PC motor owner alongside this process.
"""
import argparse
import base64
import json
import logging
from logging.handlers import RotatingFileHandler
import threading
import time
import urllib.request
from pathlib import Path

from care_controller import CareController, Settings
from detection.service import DetectionService
from motor_output import MotorOutput, DRIVE_REVERSED
from pc_dashboard import CameraFeed, read_mjpeg
from uploader import configured, start_state_reporter, start_uploader

LOG = logging.getLogger('care')
MOVEMENT = {'F': 'FORWARD', 'B': 'BACKWARD', 'L': 'TURNING', 'R': 'TURNING', 'S': 'STOPPED'}


class Runtime:
    def __init__(self, motor, detector, stop, settings):
        self.motor, self.detector, self.stop = motor, detector, stop
        self.controller = CareController(settings)
        self.lock = threading.RLock()

    def state(self):
        motor = self.motor.observation()
        with self.lock:
            return dict(operationState=self.controller.observed_state,
                        powerEnabled=self.controller.powered, taskState=self.controller.phase,
                        movementState=MOVEMENT.get(motor['ack'], 'UNKNOWN') if motor['ready'] else 'UNKNOWN')

    def command(self, command, identity, parameters=None):
        with self.lock:
            before = self.controller.powered
            self.controller.request(command, identity, parameters or {}, time.monotonic())
            if before != self.controller.powered:
                self.detector.set_processing(self.controller.powered)
        while not self.stop.wait(.05):
            with self.lock:
                result = self.controller.results.pop(identity, None)
                if result is not None:
                    LOG.info('Command %s %s: %s', command, identity, result['status'])
                    return result
        return dict(status='FAILED', operationState='UNKNOWN', errorCode='PROCESS_STOPPING')

    def run(self):
        previous = None
        while not self.stop.wait(.05):
            observation = self.detector.state()
            motor = self.motor.observation()
            with self.lock:
                command = self.controller.step(observation, motor, time.monotonic())
                self.motor.submit(command, time.monotonic()+.15, motor['generation'])
                state = (self.controller.phase, self.controller.reason, motor['ready'])
            if state != previous:
                LOG.info('Task=%s reason=%s motor=%s', *state)
                previous = state


def camera(args, feed, stop):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    headers = {}
    if args.camera_password:
        headers['Authorization'] = 'Basic ' + base64.b64encode(('robot:'+args.camera_password).encode()).decode()
    while not stop.is_set():
        try:
            request = urllib.request.Request(f'http://{args.host}:{args.camera_port}/stream.mjpg', headers=headers)
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
    parser.add_argument('--camera-password')
    parser.add_argument('--motor-token')
    parser.add_argument('--rotation', type=int, choices=(0,180), default=180)
    parser.add_argument('--config', type=Path, default=Path(__file__).with_name('care_config.json'))
    args = parser.parse_args()
    settings = Settings(**json.loads(args.config.read_text(encoding='utf-8')))
    if not configured(): parser.error('Set ROBOT_HTTP_URL, ROBOT_WS_URL, ROBOT_DEVICE_ID and ROBOT_DEVICE_TOKEN')
    log = Path(__file__).parent/'.runtime'
    log.mkdir(exist_ok=True)
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', handlers=[
        logging.StreamHandler(), RotatingFileHandler(log/'care.log', maxBytes=2_000_000, backupCount=3, encoding='utf-8')])
    LOG.warning('Reverse %.2fs / turnaround %.2fs are temporary timing values, NOT a calibrated 180-degree angle.', settings.reverse_seconds, settings.turnaround_seconds)
    stop = threading.Event()
    feed = CameraFeed(rotation=args.rotation)
    detection = DetectionService(feed, mode='both', processing=False)
    motor = MotorOutput(args.host, args.motor_token, args.control_port, DRIVE_REVERSED)
    runtime = Runtime(motor,detection,stop,settings)
    threads = []
    try:
        # Validate backend settings before starting the motor owner.
        uploader = start_uploader(stop)
        if uploader: threads.append(uploader)
        detection.start()
        motor.thread.start()
        for target, arguments in ((camera,(args,feed,stop)),(runtime.run,())):
            worker = threading.Thread(target=target,args=arguments,daemon=True)
            worker.start(); threads.append(worker)
        reporter = start_state_reporter(stop,runtime.state,runtime.command)
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

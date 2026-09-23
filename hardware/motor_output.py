"""Single motor owner with reconnect; old movement never crosses sessions."""
import logging
import threading
import time
from pc_client import RobotClient

NORMAL = dict(F='F', B='B', L='L', R='R', S='S')
REVERSED = dict(F='B', B='F', L='R', R='L', S='S')
DRIVE_REVERSED = dict(F='B', B='F', L='L', R='R', S='S')
MAPPINGS = {'normal': NORMAL, 'reversed': REVERSED, 'drive-reversed': DRIVE_REVERSED}
LOG = logging.getLogger(__name__)


def leased_command(command, pulse_until, updated, now):
    return command if 0 <= now - updated <= 0.25 and now < pulse_until else 'S'


class MotorOutput:
    def __init__(self, host, token, port, mapping, client_factory=RobotClient,
                 retry_delay=1.0):
        self.host, self.token, self.port = host, token, port
        self.mapping, self.client_factory = mapping, client_factory
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.command, self.until, self.updated = 'S', 0, 0
        self.ready, self.error, self.ack = False, None, None
        self.generation = 0
        self.ack_at = 0
        self.retry_delay = retry_delay

    def submit(self, command, until, generation=None):
        with self.lock:
            # Commands produced while disconnected cannot arm the next session.
            if self.ready and (generation is None or generation == self.generation):
                self.command, self.until, self.updated = command, until, time.monotonic()

    def status(self):
        with self.lock:
            return self.ready, self.error, self.ack

    def connection_status(self):
        with self.lock:
            return self.ready, self.error, self.ack, self.generation

    def observation(self):
        with self.lock:
            return dict(ready=self.ready, error=self.error, ack=self.ack,
                        generation=self.generation, acknowledged_at=self.ack_at)

    def run(self):
        delay = self.retry_delay
        while not self.stop.is_set():
            client = None
            try:
                LOG.info('Connecting motor transport to %s:%s', self.host, self.port)
                client = self.client_factory(self.host, self.token, self.port)
                client.drive('S')
                with self.lock:
                    self.command, self.until, self.updated = 'S', 0, 0
                    self.generation += 1
                    self.ready, self.error, self.ack = True, None, 'S'
                    self.ack_at = time.monotonic()
                LOG.info('Motor connected, session %s; stop acknowledged', self.generation)
                delay = self.retry_delay
                while not self.stop.is_set():
                    with self.lock:
                        command = leased_command(self.command, self.until, self.updated, time.monotonic())
                    client.drive(self.mapping[command])
                    with self.lock:
                        self.ack = command
                        self.ack_at = time.monotonic()
                    self.stop.wait(0.025)
            except Exception as exc:
                with self.lock:
                    self.error = type(exc).__name__ + ': ' + str(exc)
                LOG.warning('Motor disconnected: %s: %s; retry in %.1fs', type(exc).__name__, exc, delay)
            finally:
                with self.lock:
                    self.ready, self.ack = False, None
                    self.command, self.until, self.updated = 'S', 0, 0
                if client is not None:
                    try:
                        client.close()
                    except Exception:
                        LOG.exception('Motor session cleanup failed')
            if self.stop.wait(delay):
                break
            delay = min(delay * 2, 5.0)

    def close(self):
        self.submit('S', 0)
        self.stop.set()
        if self.thread.ident is not None:
            self.thread.join(timeout=7)

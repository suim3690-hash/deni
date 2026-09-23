"""Stdlib-only parent side. Shared latest-only JPEG mailbox, spawn on Windows."""
import ctypes
import multiprocessing as mp
import queue
import threading
import time
from . import config as C


class LatestFrame:
    def __init__(self, ctx, capacity=2*1024*1024):
        self.data = ctx.RawArray(ctypes.c_ubyte, capacity)
        self.length = ctx.RawValue(ctypes.c_int, 0)
        self.sequence = ctx.RawValue(ctypes.c_longlong, 0)
        self.stamp = ctx.RawValue(ctypes.c_double, 0)
        self.wall = ctx.RawValue(ctypes.c_double, 0)
        self.lock = ctx.Lock()

    def publish(self, jpeg, seq, stamp, wall):
        if len(jpeg)>len(self.data) or not self.lock.acquire(False):
            return False
        try:
            ctypes.memmove(ctypes.addressof(self.data), jpeg, len(jpeg))
            self.length.value, self.sequence.value = len(jpeg), seq
            self.stamp.value, self.wall.value = stamp, wall
            return True
        finally:
            self.lock.release()

    def take(self, last):
        if not self.lock.acquire(False):
            return None
        try:
            if self.sequence.value == last or not self.length.value:
                return None
            return (bytes(memoryview(self.data).cast('B')[:self.length.value]),
                    self.sequence.value, self.stamp.value, self.wall.value)
        finally:
            self.lock.release()


def offer(channel, state):
    try:
        channel.put_nowait(state)
    except queue.Full:
        try:
            channel.get_nowait()
        except queue.Empty:
            return
        try:
            channel.put_nowait(state)
        except queue.Full:
            pass


class DetectionService:
    def __init__(self, feed, enabled=True, mode='object', processing=True):
        if mode not in C.MODES:
            raise ValueError('Choose object or hazard')
        self.mode = mode
        self.generation = C.MODES.index(mode)
        self.feed = feed
        self.enabled = enabled
        self.processing = processing
        self.state_lock = threading.Lock()
        self.latest = dict(status='loading' if enabled else 'disabled', level=0, mode=mode, generation=self.generation)
        self.local_stop = threading.Event()
        self.process = None
        self.threads = []

    def start(self):
        if not self.enabled:
            return
        from .worker import run
        self.ctx = mp.get_context('spawn')
        self.mailbox = LatestFrame(self.ctx)
        self.results = self.ctx.Queue(maxsize=1)
        self.stop_event = self.ctx.Event()
        self.mode_code = self.ctx.Value('q', self.generation)
        self.processing_event = self.ctx.Event()
        if self.processing: self.processing_event.set()
        self.process = self.ctx.Process(target=run, args=(self.mailbox,self.results,self.stop_event,self.mode_code,self.processing_event), daemon=True)
        try:
            self.process.start()
        except Exception as exc:
            self.latest = dict(status='error', error=str(exc), level=0)
            self.process = None
            return
        for target in (self._bridge, self._collect):
            thread = threading.Thread(target=target, daemon=True)
            thread.start(); self.threads.append(thread)

    def _bridge(self):
        last = 0
        while not self.local_stop.wait(0.02):
            if not self.processing: continue
            jpeg, seq, stamp, error = self.feed.snapshot()
            now = time.monotonic()
            if jpeg and seq != last and not error and now-stamp <= C.MAX_INPUT_AGE:
                if self.mailbox.publish(jpeg,seq,stamp,time.time()-(now-stamp)):
                    last = seq

    def _collect(self):
        while not self.local_stop.is_set():
            try:
                state = self.results.get(timeout=0.2)
                with self.state_lock:
                    if state.get('generation') == self.generation:
                        self.latest = state
            except queue.Empty:
                continue
            except (EOFError, OSError):
                return

    def select_mode(self, mode):
        if mode not in C.MODES:
            raise ValueError('Choose object or hazard')
        if not self.enabled:
            raise ValueError('Restart without --no-detection')
        with self.state_lock:
            if mode == self.mode:
                return
            self.generation = (self.generation // len(C.MODES) + 1) * len(C.MODES) + C.MODES.index(mode)
            self.mode = mode
            self.latest = dict(status='switching', level=0, mode=mode, generation=self.generation)
            if hasattr(self, 'mode_code'):
                self.mode_code.value = self.generation

    def set_processing(self, enabled):
        with self.state_lock:
            self.processing = bool(enabled)
            self.latest = dict(status='waiting' if enabled else 'disabled', level=0, mode=self.mode)
            if hasattr(self, 'processing_event'):
                if enabled: self.processing_event.set()
                else: self.processing_event.clear()

    def state(self):
        with self.state_lock:
            state = dict(self.latest)
            state['mode'] = self.mode
        now = time.monotonic()
        stamp = state.get('frame_stamp')
        state['result_age'] = round(now-stamp,2) if stamp else None
        state['stale'] = stamp is None or now-stamp > C.RESULT_TTL
        if self.process is not None and self.process.exitcode is not None:
            state.update(status='error', error=state.get('error') or 'Inference process stopped')
        _, _, camera_stamp, camera_error = self.feed.snapshot()
        if self.enabled and (camera_error or now-camera_stamp > C.MAX_INPUT_AGE):
            state['camera_unavailable'] = True
        # Never turn an old positive or negative result into a current conclusion.
        state['current_level'] = state.get('level',0) if not state['stale'] and state.get('status') in ('ok','partial') and not state.get('camera_unavailable') else None
        return state

    def close(self):
        self.local_stop.set()
        if self.process is not None:
            self.stop_event.set()
            self.process.join(timeout=2)
            if self.process.is_alive():
                self.process.terminate(); self.process.join(timeout=1)
        for thread in self.threads:
            thread.join(timeout=0.4)
        if hasattr(self,'results'):
            self.results.cancel_join_thread(); self.results.close()

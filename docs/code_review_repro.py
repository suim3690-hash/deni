"""Read-only application review probes: no real device, server, or project DB.

Run: <python with hardware test dependencies> docs/code_review_repro.py
All storage is temporary; PASS means the reported problem was reproduced.
"""
import asyncio
import json
from pathlib import Path
import sys
import tempfile
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'hardware'), str(ROOT / 'hardware' / 'tests')]

from test_care_controller import CareTests
from detection.risk_engine import RiskEngine
from detection.event_store import EventStore, history
from bridge import Bridge


def relocation_reblocks():
    case = CareTests()
    case.setUp()
    case.test_relocation_captures_then_uses_marker_and_verifies_drop()
    before = case.c.phase
    case.tick(['dice'])
    assert before == 'RUNNING' and case.c.phase == 'HAZARD_PAUSED'
    return {'completed': case.c.results['move']['status'],
            'before': before, 'after_same_label': case.c.phase}


def intermittent_votes():
    engine = RiskEngine()
    obj = dict(model='object', label='coin', class_id=0, confidence=.9,
               track_id=1, bbox=[10, 10, 40, 40])
    events = []
    for frame in range(46):
        _, new = engine.evaluate([obj] if frame % 5 == 0 else [], frame * .2)
        events.extend(new)
    assert len(events) == 1 and events[0]['stable']
    return {'total_frames': 46, 'positive_frames': 10, 'empty_frames': 36,
            'stable_events': len(events)}


def history_without_outbox():
    with tempfile.TemporaryDirectory() as tmp, patch('detection.event_store.configured', return_value=False):
        store = EventStore(Path(tmp))
        store.transport = object()
        try:
            with patch('detection.event_store.enqueue_frame', side_effect=OSError('outbox unavailable')):
                try:
                    store.save(b'probe', dict(mode='object', detections=[]), 10)
                except OSError:
                    pass
            records = history(root=Path(tmp))
            assert len(records) == 1
            return {'local_history_rows': len(records), 'outbox_enqueue': 'failed'}
        finally:
            store.transport = None
            store.close()


async def command_exception_loses_result():
    def fail(*args):
        raise RuntimeError('injected handler error')

    class Socket:
        async def send(self, message):
            pass

    with tempfile.TemporaryDirectory() as tmp:
        bridge = Bridge('review', 'x' * 32, 'http://unused', 'ws://unused',
                        Path(tmp) / 'transport.sqlite3', command_handler=fail)
        try:
            identity = str(uuid4())
            message = {'type': 'COMMAND', 'payload': {
                'commandId': identity, 'command': 'RECHECK_HAZARD',
                'expiresAt': '2099-01-01T00:00:00Z', 'parameters': {}}}
            try:
                await bridge.handle(Socket(), message)
            except RuntimeError:
                pass
            started = bridge.db.execute('SELECT COUNT(*) FROM started_commands').fetchone()[0]
            pending = bridge.db.execute('SELECT COUNT(*) FROM pending_results').fetchone()[0]
            assert started == 1 and pending == 0 and identity not in bridge.inflight
            return {'started_commands': started, 'pending_results': pending,
                    'inflight': len(bridge.inflight)}
        finally:
            bridge.close()


if __name__ == '__main__':
    for name, probe in [('relocation_reblocks', relocation_reblocks),
                        ('intermittent_votes', intermittent_votes),
                        ('history_without_outbox', history_without_outbox)]:
        print(json.dumps({'probe': name, 'reproduced': True, 'evidence': probe()}))
    print(json.dumps({'probe': 'command_exception_loses_result', 'reproduced': True,
                      'evidence': asyncio.run(command_exception_loses_result())}))

import json
import sqlite3
import uuid
from datetime import datetime, timezone
from . import config as C
from uploader import configured, connection, enqueue_frame


class EventStore:
    def __init__(self, root=None):
        self.root = root or C.DATA
        self.photos = self.root/'snapshots'
        self.photos.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.root/'events.sqlite3', timeout=0.2)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, created REAL, payload TEXT)')
        self.db.commit()
        self.transport = connection() if configured() else None

    def save(self, jpeg, payload, timestamp):
        event_id = uuid.uuid4().hex
        path = self.photos/(event_id+'.jpg')
        temp = path.with_suffix('.tmp')
        record = dict(payload, id=event_id, timestamp=timestamp,
                      time=datetime.fromtimestamp(timestamp, timezone.utc).isoformat(),
                      photo='/detections/snapshots/'+event_id+'.jpg')
        try:
            temp.write_bytes(jpeg)
            temp.replace(path)
            self.db.execute('INSERT INTO events VALUES (?,?,?)', (event_id, timestamp, json.dumps(record)))
            self.db.commit()
        except Exception:
            self.db.rollback()
            temp.unlink(missing_ok=True); path.unlink(missing_ok=True)
            raise
        old = self.db.execute('SELECT id FROM events ORDER BY created DESC LIMIT -1 OFFSET ?', (C.MAX_EVENTS,)).fetchall()
        for (key,) in old:
            (self.photos/(key+'.jpg')).unlink(missing_ok=True)
            self.db.execute('DELETE FROM events WHERE id=?', (key,))
        self.db.commit()
        if self.transport is not None:
            enqueue_frame(self.transport, event_id, jpeg, payload['detections'], payload['mode'])
        return record

    def close(self):
        if self.transport is not None:
            self.transport.close()
        self.db.close()


def history(limit=30, before=None, root=None):
    path = (root or C.DATA)/'events.sqlite3'
    if not path.is_file():
        return []
    db = sqlite3.connect(f'{path.as_uri()}?mode=ro', uri=True, timeout=0.2)
    try:
        rows = db.execute('SELECT payload FROM events WHERE created < ? ORDER BY created DESC LIMIT ?',
                          (float('inf') if before is None else before, min(max(limit,1),50))).fetchall()
        return [json.loads(row[0]) for row in rows]
    finally:
        db.close()

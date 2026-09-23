import json
import re
from urllib.parse import urlsplit, parse_qs
from . import config as C
from .event_store import history


def handle_get(handler):
    url = urlsplit(handler.path)
    if not url.path.startswith('/detections/'):
        return False
    try:
        if url.path == '/detections/state':
            data = handler.server.detection.state() if handler.server.detection else {'status':'disabled'}
        elif url.path == '/detections/history':
            params = parse_qs(url.query)
            before = float(params['before'][0]) if 'before' in params else None
            data = {'events':history(before=before)}
        elif url.path == '/detections/ui.js':
            handler.reply(200,'text/javascript; charset=utf-8',(C.ROOT/'detection'/'ui.js').read_bytes())
            return True
        elif re.fullmatch(r'/detections/snapshots/[a-f0-9]{32}\.jpg',url.path):
            path = C.DATA/'snapshots'/url.path.rsplit('/',1)[1]
            if not path.is_file():
                handler.reply(404,'text/plain',b'Not found'); return True
            handler.reply(200,'image/jpeg',path.read_bytes())
            return True
        else:
            handler.reply(404,'text/plain',b'Not found'); return True
        handler.reply(200,'application/json',json.dumps(data,ensure_ascii=False).encode())
    except Exception as exc:
        handler.reply(503,'application/json',json.dumps({'error':str(exc)}).encode())
    return True

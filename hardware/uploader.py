"""Detection outbox adapter. HTTP runs separately from inference and motor loops."""
import asyncio
import logging
import os
import threading
from pathlib import Path
from uuid import UUID, uuid5
from bridge import Bridge

STORE = Path(__file__).parent / '.runtime' / 'transport.sqlite3'
LABELS = {'coin': '동전', 'marble': '구슬', 'battery': '배터리',
          'socket': '콘센트', 'wire': '전선', 'knife': '칼', 'scissors': '가위'}


def configured():
    return bool(os.environ.get('ROBOT_HTTP_URL'))


def connection(state_provider=None, command_handler=None):
    return Bridge(os.environ['ROBOT_DEVICE_ID'], os.environ['ROBOT_DEVICE_TOKEN'],
                  os.environ['ROBOT_HTTP_URL'], os.environ.get('ROBOT_WS_URL', 'ws://localhost:8080/ws/devices'), STORE,
                  state_provider, command_handler)


def enqueue_frame(bridge, event_id, jpeg, detections, mode):
    """One backend event per object; stable UUID across retry of this local event."""
    namespace = UUID(event_id)
    ids = []
    for index, detection in enumerate(detections):
        model = detection.get('model', mode).upper()
        if model not in {'OBJECT', 'HAZARD'}:
            raise ValueError('No backend mapping for model: ' + model)
        label = detection['label']
        ids.append(bridge.enqueue_detection(jpeg, LABELS.get(label, label), model,
                   str(uuid5(namespace, str(index)))))
    return ids


def start_uploader(stop):
    if not configured():
        print('HTTP upload disabled: set ROBOT_HTTP_URL, ROBOT_DEVICE_ID, ROBOT_DEVICE_TOKEN', flush=True)
        return None
    # Fail configuration before starting inference rather than silently dropping events.
    probe = connection()
    probe.close()
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    def upload():
        bridge = connection()
        try:
            while not stop.is_set():
                try:
                    bridge.flush_once()
                except Exception as exc:
                    logging.error('Upload worker error: %s', type(exc).__name__)
                stop.wait(2)
        finally:
            bridge.close()
    thread = threading.Thread(target=upload, daemon=True)
    thread.start()
    return thread


def start_state_reporter(stop, state_provider=None, command_handler=None):
    """Socket session for robot state out and backend commands in.

    Runs on its own Bridge: bridge.py gives one writer per connection, and the
    HTTP uploader already owns the other. Images do not travel this path.
    """
    if not configured():
        return None
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    def session():
        bridge = connection(state_provider, command_handler)
        async def supervise():
            socket = asyncio.create_task(bridge.run())
            async def wait_for_stop():
                while not stop.is_set():
                    await asyncio.sleep(0.2)
            waiter = asyncio.create_task(wait_for_stop())
            try:
                done, _ = await asyncio.wait([socket, waiter], return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
            finally:
                socket.cancel(); waiter.cancel()
                await asyncio.gather(socket, waiter, return_exceptions=True)
        try:
            asyncio.run(supervise())
        except Exception as exc:
            # Loud rather than a silently dead socket thread.
            logging.error('State reporter stopped: %s: %s', type(exc).__name__, exc)
        finally:
            bridge.close()
    thread = threading.Thread(target=session, daemon=True)
    thread.start()
    return thread

"""Detection outbox adapter. HTTP runs separately from inference and motor loops."""
import asyncio
import logging
import math
import os
import threading
from pathlib import Path
from uuid import UUID, uuid5
from bridge import Bridge

STORE = Path(__file__).parent / '.runtime' / 'transport.sqlite3'
LABELS = {'coin': '동전', 'marble': '구슬', 'battery': '배터리',
          'socket': '콘센트', 'wire': '전선', 'knife': '칼', 'scissors': '가위', 'dice': '주사위', 'die': '주사위'}
CROP_PADDING_RATIO = .2
MIN_CROP_PADDING_PX = 12


def configured():
    return bool(os.environ.get('ROBOT_HTTP_URL'))


def connection(state_provider=None, command_handler=None, contact_handler=None):
    return Bridge(os.environ['ROBOT_DEVICE_ID'], os.environ['ROBOT_DEVICE_TOKEN'],
                  os.environ['ROBOT_HTTP_URL'], os.environ.get('ROBOT_WS_URL', 'ws://localhost:8080/ws/devices'), STORE,
                  state_provider, command_handler, contact_handler)


def crop_detection_images(jpeg, detections):
    """Return one padded bounding-box JPEG per detection; keep the frame on decode failure."""
    try:
        import cv2
        import numpy as np
        frame = cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR)
    except (ImportError, ValueError):
        frame = None
    if frame is None:
        return [jpeg for _ in detections]
    height, width = frame.shape[:2]
    cropped = []
    for detection in detections:
        try:
            x1, y1, x2, y2 = (float(value) for value in detection['bbox'])
            left, right = sorted((x1, x2)); top, bottom = sorted((y1, y2))
            if not all(math.isfinite(value) for value in (left, top, right, bottom)) or right <= left or bottom <= top:
                raise ValueError('invalid bbox')
            pad_x = max(MIN_CROP_PADDING_PX, math.ceil((right-left) * CROP_PADDING_RATIO))
            pad_y = max(MIN_CROP_PADDING_PX, math.ceil((bottom-top) * CROP_PADDING_RATIO))
            crop_left = max(0, math.floor(left-pad_x)); crop_right = min(width, math.ceil(right+pad_x))
            crop_top = max(0, math.floor(top-pad_y)); crop_bottom = min(height, math.ceil(bottom+pad_y))
            if crop_right <= crop_left or crop_bottom <= crop_top:
                raise ValueError('empty crop')
            ok, encoded = cv2.imencode('.jpg', frame[crop_top:crop_bottom, crop_left:crop_right],
                                       [cv2.IMWRITE_JPEG_QUALITY, 90])
            if not ok:
                raise ValueError('JPEG encode failed')
            cropped.append(encoded.tobytes())
        except (KeyError, TypeError, ValueError):
            # A malformed optional crop must not discard the detection event.
            cropped.append(jpeg)
    return cropped


def enqueue_frame(bridge, event_id, jpeg, detections, mode, captured_at=None):
    """One backend event per object; stable UUID across retry of this local event."""
    namespace = UUID(event_id)
    images = crop_detection_images(jpeg, detections)
    ids = []
    for index, detection in enumerate(detections):
        model = detection.get('model', mode).upper()
        if model not in {'OBJECT', 'HAZARD'}:
            raise ValueError('No backend mapping for model: ' + model)
        label = detection['label']
        ids.append(bridge.enqueue_detection(images[index], LABELS.get(label, label), model,
                   str(uuid5(namespace, str(index))), captured_at=captured_at))
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


def start_state_reporter(stop, state_provider=None, command_handler=None, contact_handler=None):
    """Socket session for robot state out and backend commands in.

    Runs on its own Bridge: bridge.py gives one writer per connection, and the
    HTTP uploader already owns the other. Images do not travel this path.
    """
    if not configured():
        return None
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    def session():
        bridge = connection(state_provider, command_handler, contact_handler)
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

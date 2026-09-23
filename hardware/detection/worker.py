"""Heavy imports, tracking, inference and storage run only in the child."""
import os
import time
from . import config as C
from .service import offer


def unsuppressed_events(events, mask):
    return [event for event in events
            if not mask & C.ALERT_LABEL_BITS.get(event.get('label'), 0)]


def run(mailbox, output, stop, mode_code, processing=None, suppressed_alert_mask=None):
    store = None
    active_code = mode_code.value
    def publish(state):
        if mode_code.value == active_code:
            offer(output, dict(state, mode=C.MODES[active_code % len(C.MODES)], generation=active_code))
    try:
        os.environ['OMP_NUM_THREADS'] = str(C.CPU_THREADS)
        os.environ['MKL_NUM_THREADS'] = str(C.CPU_THREADS)
        import cv2
        import numpy as np
        import torch
        torch.set_num_threads(C.CPU_THREADS)
        cv2.setNumThreads(1)
        from .model_loader import Models
        from .risk_engine import RiskEngine
        from .event_store import EventStore
        from .markers import MarkerDetector
        markers = MarkerDetector()
        models = None
        engine = RiskEngine()
        storage_error = None
        try:
            store = EventStore()
        except Exception as exc:
            storage_error = str(exc)
        loaded_code = None
        last = 0
        processed = 0
        skipped_blur = 0
        previous_stamp = 0
        while not stop.is_set():
            if mode_code.value != loaded_code:
                active_code = mode_code.value
                publish(dict(status='switching', level=0))
                models = None
                engine = RiskEngine()
                previous_stamp = 0
                try:
                    models = Models(C.specs_for_mode(C.MODES[active_code % len(C.MODES)]))
                    publish(dict(status='waiting', level=0, model_errors=models.errors))
                except Exception as exc:
                    publish(dict(status='error', level=0, error=str(exc)))
                loaded_code = active_code
                if mode_code.value != active_code:
                    continue
            if models is None:
                stop.wait(0.05); continue
            if processing is not None and not processing.is_set():
                stop.wait(0.05); continue
            item = mailbox.take(last)
            if item is None:
                stop.wait(0.02); continue
            jpeg, last, stamp, wall = item
            started = time.monotonic()
            if started-stamp > C.MAX_INPUT_AGE:
                continue
            if previous_stamp and stamp-previous_stamp > C.TRACK_FORGET_SEC:
                engine = RiskEngine()
                for _, model, _, _ in models.models:
                    for tracker in getattr(model.predictor, 'trackers', []):
                        tracker.reset()
            previous_stamp = stamp
            frame = cv2.imdecode(np.frombuffer(jpeg,dtype=np.uint8),cv2.IMREAD_COLOR)
            if frame is None:
                publish(dict(status='decode_error',level=0,frame_stamp=stamp,sequence=last))
                continue
            blur = float(cv2.Laplacian(cv2.cvtColor(frame,cv2.COLOR_BGR2GRAY),cv2.CV_64F).var())
            if blur < C.BLUR_THRESHOLD:
                skipped_blur += 1
                publish(dict(status='blur',level=0,blur_score=round(blur,1),frame_stamp=stamp,
                                  sequence=last,skipped_blur=skipped_blur))
                stop.wait(0.05); continue
            objects, errors = models.infer(frame)
            if mode_code.value != active_code or (processing is not None and not processing.is_set()):
                continue
            risk, events = engine.evaluate(objects,stamp)
            # The relocation target must remain visible to local control for drop
            # verification, but seeing it again after backing is not a new alert.
            mask = suppressed_alert_mask.value if suppressed_alert_mask is not None else 0
            events = unsuppressed_events(events, mask)
            processed += 1
            elapsed = time.monotonic()-started
            status = 'error' if all(name in errors for name, *_ in models.models) else ('partial' if errors else 'ok')
            state = dict(risk,status=status,frame_stamp=stamp,sequence=last,
                         markers=markers.detect(frame), frame_width=frame.shape[1], frame_height=frame.shape[0],
                         frame_time=wall,inference_ms=round(elapsed*1000),blur_score=round(blur,1),
                         processed=processed,skipped_blur=skipped_blur,model_errors=errors,
                         storage_error=storage_error,events_created=0)
            # Publish alert before snapshot encoding/disk I/O.
            publish(state)
            if events and store is not None:
                try:
                    picture = frame.copy()
                    for d in objects:
                        if d['confidence'] < C.ALERT_CONF: continue
                        x1,y1,x2,y2 = [int(v) for v in d['bbox']]
                        cv2.rectangle(picture,(x1,y1),(x2,y2),(0,180,255),2)
                        text = f"{d['label']} {d['confidence']:.2f} ID:{d['track_id']}"
                        cv2.putText(picture,text,(max(0,x1),max(16,y1-5)),cv2.FONT_HERSHEY_SIMPLEX,0.45,(0,180,255),1)
                    ok, encoded = cv2.imencode('.jpg',picture,[cv2.IMWRITE_JPEG_QUALITY,90])
                    if not ok: raise OSError('JPEG encode failed')
                    # Local history keeps the annotated frame. The backend uploader
                    # creates one padded bounding-box crop per alerted object.
                    if mode_code.value != active_code or (processing is not None and not processing.is_set()):
                        continue
                    store.save(encoded.tobytes(),dict(mode=C.MODES[active_code % len(C.MODES)], sequence=last,level=max(d['level'] for d in events),
                        detections=events,person_detected=risk['person_detected'],model_errors=errors,
                        inference_ms=state['inference_ms']),wall)
                    storage_error = None
                    state.update(events_created=1,storage_error=None)
                except Exception as exc:
                    storage_error = str(exc); state['storage_error'] = storage_error
                publish(state)
            stop.wait(max(0,1/C.MAX_INFERENCE_FPS-(time.monotonic()-started)))
    except Exception as exc:
        publish(dict(status='error',level=0,error=f'{type(exc).__name__}: {exc}'))
        # Keep error readable instead of racing a dead-child status update.
        stop.wait()
    finally:
        if store is not None: store.close()

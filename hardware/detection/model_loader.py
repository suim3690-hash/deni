"""Only this module knows Ultralytics. All models inspect the SAME frame."""
from . import config as C


def iou(a, b):
    w = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    h = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = w*h
    return inter / max(1e-9, (a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-inter)


class Models:
    def __init__(self, specs=None):
        from ultralytics import YOLO
        self.models = []
        self.errors = {}
        for name, path, wanted in (C.MODEL_SPECS if specs is None else specs):
            try:
                if not path.is_file():
                    raise FileNotFoundError(f'{path.name} missing; run setup_detection.py')
                model = YOLO(str(path))
                if model.task != 'detect':
                    raise ValueError('Object detection checkpoint required')
                names = model.names
                classes = [k for k, v in names.items() if v in wanted] if wanted else None
                if wanted and len(classes) != len(wanted):
                    raise ValueError('Requested classes not found in checkpoint')
                raw = []
                # Capture before the callback installed by model.track: urgent alerts
                # must not depend on ByteTrack confirming a new track.
                def capture(predictor, output=raw, source=name):
                    output.clear()
                    for result in predictor.results:
                        if result.boxes is not None:
                            for box, cls, conf in zip(result.boxes.xyxy.cpu().tolist(),
                                    result.boxes.cls.int().cpu().tolist(), result.boxes.conf.cpu().tolist()):
                                output.append(dict(model=source, class_id=cls, label=result.names[cls],
                                                   confidence=conf, bbox=box, track_id=None))
                model.add_callback('on_predict_postprocess_end', capture)
                self.models.append((name, model, classes, raw))
            except Exception as exc:
                self.errors[name] = str(exc)
        if not self.models:
            raise RuntimeError(str(self.errors))

    def infer(self, frame):
        objects = []
        for name, model, classes, raw in self.models:
            try:
                result = model.track(source=frame, device='cpu', imgsz=C.IMAGE_SIZE,
                                     conf=C.DETECT_CONF, classes=classes, verbose=False,
                                     save=False, persist=True,
                                     tracker=str(C.ROOT/'detection'/'bytetrack.yaml'))[0]
                tracks = []
                if result.boxes is not None and result.boxes.is_track:
                    tracks = list(zip(result.boxes.xyxy.cpu().tolist(), result.boxes.id.int().cpu().tolist()))
                used = set()
                for obj in raw:
                    candidates = [(iou(obj['bbox'], box), tid) for box, tid in tracks if tid not in used]
                    if candidates:
                        overlap, tid = max(candidates)
                        if overlap > 0.5:
                            obj['track_id'] = tid
                            used.add(tid)
                    objects.append(dict(obj))
                self.errors.pop(name, None)
            except Exception as exc:
                self.errors[name] = str(exc)
        return objects, dict(self.errors)

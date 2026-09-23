"""Temporal class stabilization and cooldown; independent of frame transport."""
from . import config as C
from .stabilization import ClassStabilizer
from .model_loader import iou


class RiskEngine:
    def __init__(self):
        self.stabilizer = ClassStabilizer(C.STABLE_WINDOW, C.STABLE_VOTES)
        self.seen = {}
        self.alerted = {}
        self.labels = {}
        self.unknown = []
        self.next_unknown = 0

    def reset_alert_labels(self, labels):
        """Allow the next stable sighting after a confirmed direct removal to alert immediately."""
        targets = set(labels)
        for key, label in list(self.labels.items()):
            if label in targets:
                self.alerted.pop(key, None)

    def evaluate(self, detections, now):
        # Real-time TTL, in addition to ByteTrack's processed-frame buffer.
        for key, stamp in list(self.seen.items()):
            if now-stamp > C.TRACK_FORGET_SEC:
                self.seen.pop(key, None)
                self.stabilizer.history.pop(key, None)
                self.stabilizer.confirmed.pop(key, None)
        self.alerted = {k:v for k,v in self.alerted.items() if now-v[0] <= C.TRACK_FORGET_SEC}
        active_keys = self.seen.keys() | self.alerted.keys()
        self.labels = {key: label for key, label in self.labels.items() if key in active_keys}
        self.unknown = [u for u in self.unknown if now-u['time'] <= C.TRACK_FORGET_SEC]
        used_unknown = set()
        people = any(d['label']=='person' and d['confidence'] >= C.EMERGENCY_CONF for d in detections)
        hazards, events = [], []
        for d in detections:
            label, confidence = d['label'], d['confidence']
            if label not in C.RISK_LEVELS or confidence < C.ALERT_CONF:
                continue
            obj = dict(d)
            if d['track_id'] is None:
                # Short-term visual fallback for raw detections without a tracker ID.
                matches = [(iou(d['bbox'], u['bbox']), u) for u in self.unknown
                           if u['model']==d['model'] and u['label']==label and u['key'] not in used_unknown]
                best = max(matches, key=lambda p:p[0], default=(0, None))
                if best[0] > 0.3:
                    u = best[1]
                else:
                    self.next_unknown += 1
                    u = dict(model=d['model'], label=label, key=(d['model'], 'raw', self.next_unknown))
                    self.unknown.append(u)
                u.update(bbox=d['bbox'], time=now)
                key = u['key']; used_unknown.add(key)
            else:
                key = (d['model'], 'track', d['track_id'])
                # Carry cooldown over from a matching raw alert when an ID appears.
                for u in self.unknown:
                    if u['model']==d['model'] and u['label']==label and iou(d['bbox'], u['bbox'])>0.5:
                        if key not in self.alerted and u['key'] in self.alerted:
                            self.alerted[key] = self.alerted[u['key']]
                        break
            self.seen[key] = now
            self.labels[key] = label
            self.stabilizer.update(key, d['class_id'], confidence)
            confirmed = self.stabilizer.confirmed.get(key)
            stable = bool(confirmed and confirmed[0] == d['class_id'])
            urgent = people and confidence >= C.EMERGENCY_CONF and label in C.PERSON_ESCALATION_CLASSES
            level = 3 if urgent else C.RISK_LEVELS[label]
            obj.update(level=level, stable=stable, reason='person_and_object' if urgent else 'object_detected')
            hazards.append(obj)
            last_time, last_level = self.alerted.get(key, (-1e9, 0))
            if (urgent or stable) and (now-last_time >= C.ALERT_COOLDOWN_SEC or level > last_level):
                self.alerted[key] = (now, level)
                events.append(obj)
        return dict(level=max((d['level'] for d in hazards), default=0),
                    person_detected=people, hazards=hazards), events

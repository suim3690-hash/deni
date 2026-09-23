"""Rolling class votes adapted from the supplied ClassStabilizer."""
from collections import Counter, deque


class ClassStabilizer:
    def __init__(self, window=10, votes=8):
        if not window // 2 < votes <= window:
            raise ValueError('Require window / 2 < votes <= window')
        self.window = window
        self.votes = votes
        self.history = {}
        self.confirmed = {}

    def update(self, track_id, class_id, confidence):
        """Return (class, mean confidence) on first confirmation or class change.

        Missing detections do not vote. A full window of observations is
        required; confidence is averaged only over the winning class.
        """
        if track_id is None:
            return None
        history = self.history.setdefault(track_id, deque(maxlen=self.window))
        history.append((class_id, confidence))
        if len(history) < self.window:
            return None
        winner, count = Counter(cls for cls, _ in history).most_common(1)[0]
        if count < self.votes:
            return None
        score = sum(conf for cls, conf in history if cls == winner) / count
        old = self.confirmed.get(track_id)
        self.confirmed[track_id] = (winner, score)
        return (winner, score) if old is None or old[0] != winner else None

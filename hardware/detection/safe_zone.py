"""Only a single, currently visible object near a visible drop marker is exempt."""
import math


def safe_labels(objects, markers, allowed, width, height, marker_id, radius):
    marker = next((m for m in markers if m.get('id') == marker_id), None)
    if marker is None or marker.get('skew', 1) > .5 or width <= 0 or height <= 0:
        return set()
    safe = set()
    for label in allowed:
        targets = [obj for obj in objects if obj.get('label') == label]
        if len(targets) != 1:
            continue
        try:
            x1, y1, x2, y2 = targets[0]['bbox']
            mx, my = marker['centre']
            distance = math.hypot(((x1+x2)/2-mx)/width, ((y1+y2)/2-my)/height)
            if math.isfinite(distance) and distance <= radius:
                safe.add(label)
        except (KeyError, TypeError, ValueError):
            continue
    return safe

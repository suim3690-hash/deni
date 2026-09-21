"""ArUco destination markers. Detection only; no motor command is issued here.

Markers are fiducials, not a learned model: detection is deterministic and needs
no dataset, weights or warm-up. Bearing and range come from image geometry, so
no camera calibration is required. Calibrate only if a future task needs metric
pose (docking perpendicular to a marker); "near the marker" does not.
"""
import cv2
import numpy as np

# 4x4 carries fewer bits than 5x5/6x6, so it survives small pixel sizes better.
# 50 IDs is far more than this robot needs and keeps inter-ID distance high.
DICTIONARY = cv2.aruco.DICT_4X4_50
# Reserved meanings. Unlisted IDs are reported but carry no role yet.
DROP_OFF_ID = 0
# Below this the corner estimate is too noisy to steer on at 640x480.
MIN_SIDE_PX = 24.0


def _detector():
    return cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(DICTIONARY),
                                   cv2.aruco.DetectorParameters())


class MarkerDetector:
    """One instance per worker. Not thread safe, matching the inference worker."""

    def __init__(self, min_side_px=MIN_SIDE_PX):
        self.detector = _detector()
        self.min_side_px = min_side_px

    def detect(self, frame):
        """Return markers sorted nearest-first, using apparent size as range.

        Caller must pass the same frame orientation used for display, so a
        bearing of +0.2 means the marker sits right of centre on screen too.
        """
        height, width = frame.shape[:2]
        grey = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
        corners, ids, _ = self.detector.detectMarkers(grey)
        if ids is None:
            return []
        found = []
        for quad, marker in zip(corners, ids.ravel().tolist()):
            points = quad[0]
            sides = [float(np.linalg.norm(points[i]-points[(i+1) % 4])) for i in range(4)]
            side = sum(sides)/4
            if side < self.min_side_px:
                continue
            centre = points.mean(axis=0)
            found.append(dict(
                id=int(marker),
                role='drop_off' if marker == DROP_OFF_ID else 'unassigned',
                centre=[round(float(centre[0]), 1), round(float(centre[1]), 1)],
                # -1 hard left, 0 centred, +1 hard right: the steering input.
                bearing=round(float((centre[0]-width/2)/(width/2)), 3),
                # Apparent size stands in for range; bigger means closer.
                side_px=round(side, 1),
                fill=round(float(side*side/(width*height)), 5),
                # A skewed quad means the robot is off to one side of the marker.
                skew=round((max(sides)-min(sides))/side, 3) if side else 0.0,
                corners=points.round(1).tolist()))
        found.sort(key=lambda m: -m['side_px'])
        return found


def draw(frame, markers):
    """Annotate a copy for the dashboard; never mutate the shared frame."""
    picture = frame.copy()
    for marker in markers:
        points = np.array(marker['corners'], dtype=np.int32)
        colour = (0, 220, 0) if marker['role'] == 'drop_off' else (200, 200, 0)
        cv2.polylines(picture, [points], True, colour, 2)
        x, y = int(marker['centre'][0]), int(marker['centre'][1])
        cv2.circle(picture, (x, y), 4, colour, -1)
        cv2.putText(picture, f"id{marker['id']} b{marker['bearing']:+.2f} {marker['side_px']:.0f}px",
                    (max(0, points[:, 0].min()), max(16, points[:, 1].min()-6)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, colour, 1)
    height, width = picture.shape[:2]
    cv2.line(picture, (width//2, 0), (width//2, height), (120, 120, 120), 1)
    return picture


def sheet(ids, pixels=240, margin=40, columns=3):
    """Printable page of markers. Print with a white border left intact."""
    dictionary = cv2.aruco.getPredefinedDictionary(DICTIONARY)
    cells = []
    for marker in ids:
        image = cv2.aruco.generateImageMarker(dictionary, marker, pixels)
        cell = np.full((pixels+margin*2, pixels+margin*2), 255, np.uint8)
        cell[margin:margin+pixels, margin:margin+pixels] = image
        cv2.putText(cell, f'ID {marker}', (margin, margin+pixels+28),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, 0, 1)
        cells.append(cell)
    rows = []
    for start in range(0, len(cells), columns):
        row = cells[start:start+columns]
        while len(row) < columns:
            row.append(np.full_like(cells[0], 255))
        rows.append(np.hstack(row))
    return np.vstack(rows)

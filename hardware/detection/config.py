import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEIGHTS = ROOT / 'weights'
DATA = ROOT / 'data'
# Tune using scores from actual 640x480 Pi frames; 0 disables blur rejection.
# Sharp frames of a flat scene (floor, desk surface) score 12-45 on this Pi camera,
# so DETECTION_BLUR_THRESHOLD overrides this per run while tuning.
BLUR_THRESHOLD = float(os.environ.get('DETECTION_BLUR_THRESHOLD', '60.0'))
DETECT_CONF = 0.10
ALERT_CONF = 0.35
EMERGENCY_CONF = 0.50
IMAGE_SIZE = 640
CPU_THREADS = 2
MAX_INFERENCE_FPS = 5.0
RESULT_TTL = 3.0
MAX_INPUT_AGE = 0.7
ALERT_COOLDOWN_SEC = 10.0
TRACK_FORGET_SEC = 15.0
STABLE_WINDOW = 10
STABLE_VOTES = 8
MAX_EVENTS = 500
# Detection priorities, not medical risk estimates. battery is not a subtype classifier.
RISK_LEVELS = {'coin': 1, 'marble': 1, 'battery': 2, 'dice': 1, 'die': 1,
               'knife': 2, 'scissors': 2, 'socket': 1, 'wire': 1}
# Remove socket/wire from this set to keep electrical objects at their base level.
PERSON_ESCALATION_CLASSES = set(RISK_LEVELS)
MODEL_SPECS = [('object', WEIGHTS / 'object.pt', None),
               ('hazard', WEIGHTS / 'hazard.pt', None)]

MODES = ('object', 'hazard', 'both')

def specs_for_mode(mode):
    if mode not in MODES:
        raise ValueError('Choose object or hazard')
    return list(MODEL_SPECS) if mode == 'both' else [spec for spec in MODEL_SPECS if spec[0] == mode]

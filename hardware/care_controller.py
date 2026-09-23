"""Robot task state machine. No networking, model imports or physical side effects."""
from dataclasses import dataclass
import math

SWALLOW = {'coin', 'marble', 'battery', 'dice', 'die'}
LABELS = {'동전': 'coin', '구슬': 'marble', '배터리': 'battery', '주사위': 'dice'}
RELOCATION_PHASES = {'ALIGNING_TARGET', 'CAPTURING', 'SEEKING_MARKER', 'PUSHING_TO_MARKER',
                     'BACKING', 'VERIFYING_DROP', 'TURNING_AROUND'}


@dataclass(frozen=True)
class Settings:
    removal_absence_seconds: float = 2.0
    observation_ttl: float = 3.0
    max_observation_gap: float = 1.0
    marker_id: int = 0
    marker_stop_fill: float = .13126
    bearing_deadband: float = .12
    turn_pulse_seconds: float = .12
    settle_seconds: float = .35
    capture_seconds: float = .8
    marker_search_timeout_seconds: float = 12.0
    reverse_seconds: float = 1.0
    drop_verify_seconds: float = 1.0
    drop_verify_timeout_seconds: float = 10.0
    drop_verify_radius_ratio: float = .3
    turnaround_seconds: float = 1.0
    action_timeout_seconds: float = 120.0

    def __post_init__(self):
        for name, value in vars(self).items():
            if name == 'marker_id':
                if type(value) is not int or not 0 <= value < 50: raise ValueError('Invalid marker ID')
            elif not math.isfinite(value) or value <= 0:
                raise ValueError(name + ' must be positive and finite')
        if (self.removal_absence_seconds < 2 or self.marker_stop_fill >= 1 or self.bearing_deadband >= 1
                or self.drop_verify_radius_ratio >= 1):
            raise ValueError('Invalid absence/fill/bearing settings')


class CareController:
    def __init__(self, settings=None):
        self.settings = settings or Settings()
        self.powered = False
        self.phase = 'OFF'
        self.blocked = set()
        self.action = None
        self.finishing = False
        self.finished_at = 0
        self.control = None
        self.results = {}
        self.absent_since = None
        self.last_sequence = None
        self.last_observation = None
        self.started = 0
        self.last_tick = None
        self.timed_remaining = 0
        self.last_output = 'S'
        self.pulse_until = self.settle_until = 0
        self.phase_started = 0
        self.verified_since = None
        self.generation = None
        self.require_frame_after = 0
        self.reason = 'POWER OFF'
        self.observed_state = 'UNKNOWN'

    def _complete_action(self, status, code=None):
        if self.action:
            command, identity, params = self.action
            result = dict(status=status, operationState=self.observed_state,
                          hazardId=params['hazardId'])
            if status == 'SUCCEEDED':
                result.update(operationState='PAUSED' if self.blocked else 'RUNNING')
                if command == 'RECHECK_HAZARD':
                    result.update(hazardPresent=False, absenceDurationMs=round(self.settings.removal_absence_seconds*1000))
                else:
                    result.update(relocationCompleted=True)
            if code: result['errorCode'] = code
            self.results[identity] = result
            self.action = None
        self.finishing = False
        self.absent_since = None
        self.verified_since = None

    def _start_phase(self, phase, now, duration=0):
        self.phase, self.phase_started, self.timed_remaining = phase, now, duration
        self.pulse_until = self.settle_until = 0

    def _steer(self, bearing, now, forward):
        cfg = self.settings
        if now < self.settle_until:
            return 'S'
        if abs(bearing) <= cfg.bearing_deadband:
            self.pulse_until = 0
            return 'F' if forward else 'S'
        if not self.pulse_until:
            self.pulse_until = now + cfg.turn_pulse_seconds
        if now < self.pulse_until:
            return 'R' if bearing > 0 else 'L'
        self.pulse_until = 0
        self.settle_until = now + cfg.settle_seconds
        return 'S'

    def _drop_is_verified(self, targets, marker, observation):
        if len(targets) != 1 or marker is None or marker.get('skew', 1) > .5:
            return False
        try:
            width, height = float(observation['frame_width']), float(observation['frame_height'])
            x1, y1, x2, y2 = (float(value) for value in targets[0]['bbox'])
            marker_x, marker_y = (float(value) for value in marker['centre'])
            object_x, object_y = (x1+x2)/2, (y1+y2)/2
            distance = math.hypot((object_x-marker_x)/width, (object_y-marker_y)/height)
            return distance <= self.settings.drop_verify_radius_ratio
        except (KeyError, TypeError, ValueError, ZeroDivisionError):
            return False

    def _finish_motion(self, now):
        self.blocked.discard(self.action[2]['label'])
        self.finishing, self.finished_at = True, now
        self.phase = 'HAZARD_PAUSED' if self.blocked else 'RUNNING'
        self.require_frame_after = now

    def request(self, command, identity, params, now):
        def reject(code):
            self.results[identity] = dict(status='FAILED', operationState=self.observed_state, errorCode=code)
        if identity in self.results: return
        if command in ('POWER_OFF', 'PAUSE'):
            self._complete_action('FAILED', 'INTERRUPTED_BY_USER')
            if self.control:
                self.results[self.control[1]] = dict(status='FAILED', operationState='UNKNOWN', errorCode='SUPERSEDED')
            self.powered = command != 'POWER_OFF' and self.powered
            self.phase = 'OFF' if not self.powered else 'PAUSED'
            self.control = (command, identity, now)
            self.last_output = 'S'
            return
        if self.action or self.control:
            reject('ACTION_IN_PROGRESS'); return
        if command in ('POWER_ON', 'RESUME'):
            if command == 'RESUME' and (not self.powered or self.blocked):
                reject('HAZARD_UNRESOLVED' if self.blocked else 'POWER_OFF'); return
            self.powered = True
            self.phase = 'HAZARD_PAUSED' if self.blocked else 'RUNNING'
            self.require_frame_after = now
            self.control = (command, identity, now)
            return
        if command not in ('RECHECK_HAZARD', 'RELOCATE'):
            reject('UNSUPPORTED_COMMAND'); return
        label = LABELS.get(params.get('objectLabel'))
        if not label or not params.get('hazardId'):
            reject('UNSUPPORTED_TARGET'); return
        if not self.powered:
            reject('POWER_OFF'); return
        if self.phase not in ('HAZARD_PAUSED', 'PAUSED'):
            reject('DEVICE_NOT_PAUSED'); return
        self.blocked.add(label)
        self.action = (command, identity, dict(params, label=label))
        self.phase = 'RECHECKING' if command == 'RECHECK_HAZARD' else 'ALIGNING_TARGET'
        self.phase_started = now
        self.started = now
        self.absent_since = None
        self.last_observation = None
        self.require_frame_after = now
        self.pulse_until = self.settle_until = 0

    def step(self, observation, motor, now):
        cfg = self.settings
        dt = 0 if self.last_tick is None else max(0, min(now-self.last_tick, .2))
        self.last_tick = now
        ready = motor.get('ready', False)
        ack = motor.get('ack')
        self.observed_state = ('UNKNOWN' if not ready else
            'PAUSED' if ack == 'S' else 'RELOCATING' if self.phase in RELOCATION_PHASES else 'RUNNING')
        if not ready:
            self.last_output = 'S'
            self.absent_since = None
            self.verified_since = None
            self.require_frame_after = now
            self.reason = 'RECONNECTING'
            return 'S'
        if self.generation != motor.get('generation'):
            self.generation = motor.get('generation')
            self.require_frame_after = now
            self.absent_since = None
            self.verified_since = None
            self.last_output = 'S'
            self.pulse_until = self.settle_until = 0
        if self.control:
            kind, identity, requested = self.control
            if now-requested > 8:
                self.results[identity] = dict(status='FAILED', operationState=self.observed_state, errorCode='STATE_NOT_CONFIRMED')
                self.control = None
                self.phase = 'PAUSED' if self.powered else 'OFF'
            elif motor.get('acknowledged_at', 0) > requested:
                # POWER_ON confirms the mode change while stopped; model warm-up
                # must not cancel autonomous intent after eight seconds.
                # Movement still waits for valid detection below.
                stopped = kind in ('PAUSE', 'POWER_OFF', 'POWER_ON') or self.phase == 'HAZARD_PAUSED'
                if (stopped and ack == 'S') or ((not stopped or kind == 'POWER_ON') and self.last_output == 'F' and ack == 'F'):
                    self.results[identity] = dict(status='SUCCEEDED', operationState='PAUSED' if ack == 'S' else 'RUNNING')
                    self.control = None
        if not self.powered:
            self.reason = 'POWER OFF'; self.last_output = 'S'; return 'S'
        if self.action and now-self.started > cfg.action_timeout_seconds:
            self._complete_action('FAILED', 'ACTION_TIMEOUT')
            self.phase = 'HAZARD_PAUSED'
        stamp = observation.get('frame_stamp', 0)
        valid = (observation.get('status') == 'ok' and not observation.get('camera_unavailable')
                 and isinstance(stamp, (int, float)) and 0 <= now-stamp <= cfg.observation_ttl
                 and stamp > self.require_frame_after)
        if not valid:
            self.absent_since = None
            self.verified_since = None
            self.last_output = 'S'
            if observation.get('camera_unavailable'):
                self.reason = 'CAMERA UNAVAILABLE'
            elif observation.get('status') != 'ok':
                self.reason = 'DETECTION ' + str(observation.get('status', 'missing')).upper()
            elif not isinstance(stamp, (int, float)) or not 0 <= now-stamp <= cfg.observation_ttl:
                self.reason = 'DETECTION STALE'
            else:
                self.reason = 'WAIT FOR POST-COMMAND FRAME'
            return 'S'
        seen = [dict(obj, label='dice' if obj['label']=='die' else obj['label'])
                for obj in observation.get('hazards', []) if obj.get('label') in SWALLOW]
        labels = {obj['label'] for obj in seen}
        self.blocked.update(labels)
        if self.phase == 'RUNNING' and self.blocked:
            self.phase = 'HAZARD_PAUSED'
        new_frame = observation.get('sequence') != self.last_sequence
        if new_frame:
            self.last_sequence = observation.get('sequence')
        if self.finishing and self.action[0] == 'RECHECK_HAZARD' and self.action[2]['label'] in labels:
            # A fresh redetection invalidates absence before completion ACK.
            self.finishing = False
            self.phase = 'RECHECKING'
            self.absent_since = None
            self.last_observation = None
        if self.finishing and motor.get('acknowledged_at', 0)>self.finished_at:
            if (self.phase == 'RUNNING' and self.last_output == 'F' and ack == 'F') or (self.phase == 'HAZARD_PAUSED' and ack == 'S'):
                self._complete_action('SUCCEEDED')
        # 감지 후 정지는 사용자의 처리 선택 전까지 유지한다. 물체가 사라져도
        # RECHECK_HAZARD 성공 없이 자동 재개하면 DB의 ACTIVE 위험과 어긋난다.
        command = 'S'
        if self.phase == 'RECHECKING' and new_frame:
            label = self.action[2]['label']
            if label in labels:
                self.absent_since = None
            else:
                if self.last_observation is None or stamp-self.last_observation > cfg.max_observation_gap:
                    self.absent_since = stamp
                if self.absent_since is None: self.absent_since = stamp
                if stamp-self.absent_since >= cfg.removal_absence_seconds:
                    self._finish_motion(now)
                    self.last_output = 'S'
                    return 'S'
            self.last_observation = stamp
        if self.phase == 'ALIGNING_TARGET':
            label = self.action[2]['label']
            targets = [obj for obj in seen if obj['label']==label]
            if not targets:
                self.reason = 'TARGET NOT VISIBLE'
            elif len(targets) != 1:
                self.reason = 'MULTIPLE TARGETS OF SAME CLASS'
            else:
                x1, _, x2, _ = targets[0]['bbox']
                bearing = ((x1+x2)/2 / observation['frame_width'])*2-1
                if abs(bearing) > cfg.bearing_deadband:
                    command = self._steer(bearing, now, False)
                else:
                    # From here the object may disappear below the camera because
                    # the semicircular arm has taken it in. Do not require it again
                    # until the drop-off verification phase.
                    self._start_phase('CAPTURING', now, cfg.capture_seconds)
                    command = 'F'
        elif self.phase == 'CAPTURING':
            if self.last_output == 'F' and ack == 'F' and now-motor.get('acknowledged_at', 0)<.25:
                self.timed_remaining -= dt
            if self.timed_remaining <= 0:
                self._start_phase('SEEKING_MARKER', now)
                command = 'S'
            else:
                command = 'F'
        elif self.phase in ('SEEKING_MARKER', 'PUSHING_TO_MARKER'):
            marker = next((m for m in observation.get('markers', []) if m['id']==cfg.marker_id), None)
            if marker is None:
                if self.phase != 'SEEKING_MARKER':
                    self._start_phase('SEEKING_MARKER', now)
                    command = 'S'
                elif now-self.phase_started >= cfg.marker_search_timeout_seconds:
                    self._complete_action('FAILED', 'MARKER_NOT_FOUND')
                    self.phase = 'HAZARD_PAUSED'
                    command = 'S'
                else:
                    # The demo drop zone is searched clockwise. Rotation is pulsed;
                    # the robot never drives forward without a visible marker.
                    command = self._steer(1.0, now, False)
            elif marker.get('skew', 1) > .5:
                self.reason = 'MARKER TOO SKEWED'
            elif marker['fill'] >= cfg.marker_stop_fill:
                self._start_phase('BACKING', now, cfg.reverse_seconds)
                command = 'S'
            elif self.phase == 'SEEKING_MARKER':
                command = self._steer(marker['bearing'], now, False)
                if abs(marker['bearing']) <= cfg.bearing_deadband:
                    self._start_phase('PUSHING_TO_MARKER', now)
                    command = 'F'
            else:
                command = self._steer(marker['bearing'], now, True)
        elif self.phase in ('BACKING', 'TURNING_AROUND'):
            desired = 'B' if self.phase == 'BACKING' else 'R'
            # Count only periods with fresh ACKs, not disconnected wall time.
            if self.last_output == desired and ack == desired and now-motor.get('acknowledged_at', 0)<.25:
                self.timed_remaining -= dt
            if self.timed_remaining <= 0:
                if self.phase == 'BACKING':
                    self._start_phase('VERIFYING_DROP', now)
                    self.require_frame_after = now
                    self.verified_since = None
                    self.last_observation = None
                else:
                    self._finish_motion(now)
                command = 'S'
            else:
                command = desired
        elif self.phase == 'VERIFYING_DROP' and new_frame:
            label = self.action[2]['label']
            targets = [obj for obj in seen if obj['label']==label]
            marker = next((m for m in observation.get('markers', []) if m['id']==cfg.marker_id), None)
            verified = self._drop_is_verified(targets, marker, observation)
            if verified:
                if self.last_observation is None or stamp-self.last_observation > cfg.max_observation_gap:
                    self.verified_since = stamp
                if self.verified_since is None:
                    self.verified_since = stamp
                self.reason = 'DROP TARGET VERIFIED'
                if stamp-self.verified_since >= cfg.drop_verify_seconds:
                    self._start_phase('TURNING_AROUND', now, cfg.turnaround_seconds)
            else:
                self.verified_since = None
                self.reason = ('DROP TARGET NOT VISIBLE' if not targets else
                               'MULTIPLE DROP TARGETS' if len(targets) != 1 else
                               'DROP MARKER NOT VISIBLE' if marker is None else 'DROP TARGET OUTSIDE SAFE ZONE')
            self.last_observation = stamp
            if self.phase == 'VERIFYING_DROP' and now-self.phase_started >= cfg.drop_verify_timeout_seconds:
                self._complete_action('FAILED', 'DROP_NOT_VERIFIED')
                self.phase = 'HAZARD_PAUSED'
        elif self.phase == 'RUNNING':
            command = 'F'
        self.last_output = command
        if command != 'S' or self.phase in ('HAZARD_PAUSED','PAUSED','RECHECKING'):
            self.reason = self.phase
        return command

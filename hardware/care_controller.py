"""Robot task state machine. No networking, model imports or physical side effects."""
from dataclasses import dataclass
import math

SWALLOW = {'coin', 'marble', 'battery', 'dice', 'die'}
LABELS = {'동전': 'coin', '구슬': 'marble', '배터리': 'battery', '주사위': 'dice'}


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
    reverse_seconds: float = 1.0
    turnaround_seconds: float = 1.0
    action_timeout_seconds: float = 120.0

    def __post_init__(self):
        for name, value in vars(self).items():
            if name == 'marker_id':
                if type(value) is not int or not 0 <= value < 50: raise ValueError('Invalid marker ID')
            elif not math.isfinite(value) or value <= 0:
                raise ValueError(name + ' must be positive and finite')
        if self.removal_absence_seconds < 2 or self.marker_stop_fill >= 1 or self.bearing_deadband >= 1:
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
        # 정지 이력을 스스로 푸는 데 쓰는 미검출 추적. 재확인 요청의 absent_since와 분리한다.
        self.clear_since = None
        self.clear_observed = None
        self.last_sequence = None
        self.last_observation = None
        self.started = 0
        self.last_tick = None
        self.timed_remaining = 0
        self.last_output = 'S'
        self.pulse_until = self.settle_until = 0
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
        self.clear_since = None
        self.clear_observed = None

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
        self.phase = 'RECHECKING' if command == 'RECHECK_HAZARD' else 'PUSHING'
        self.started = now
        self.absent_since = None
        self.clear_since = None
        self.clear_observed = None
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
            'PAUSED' if ack == 'S' else 'RELOCATING' if self.phase in ('PUSHING','BACKING','TURNING_AROUND') else 'RUNNING')
        if not ready:
            self.last_output = 'S'
            self.absent_since = None
            self.clear_since = None
            self.require_frame_after = now
            self.reason = 'RECONNECTING'
            return 'S'
        if self.generation != motor.get('generation'):
            self.generation = motor.get('generation')
            self.require_frame_after = now
            self.absent_since = None
            self.clear_since = None
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
            self.clear_since = None
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
        # 전원을 다시 켰을 때 지난 정지 이력이 남아 있어도, 새 영상에서 삼킴 위험물이
        # 연속으로 보이지 않으면 스스로 주행을 재개한다. 처리 요청 중에는 손대지 않는다.
        if self.phase == 'HAZARD_PAUSED' and not self.action and not self.finishing and new_frame:
            if labels:
                self.clear_since = None
            else:
                if self.clear_observed is None or stamp-self.clear_observed > cfg.max_observation_gap:
                    self.clear_since = stamp
                if self.clear_since is None:
                    self.clear_since = stamp
                if stamp-self.clear_since >= cfg.removal_absence_seconds:
                    self.blocked.clear()
                    self.phase = 'RUNNING'
                    self.clear_since = None
            self.clear_observed = stamp
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
        if self.phase == 'PUSHING':
            label = self.action[2]['label']
            targets = [obj for obj in seen if obj['label']==label]
            marker = next((m for m in observation.get('markers', []) if m['id']==cfg.marker_id), None)
            # An explicit relocation selects this class even when other classes
            # are visible. Keep those hazards blocked for after this action.
            if marker and marker['fill'] >= cfg.marker_stop_fill:
                self.phase, self.timed_remaining = 'BACKING', cfg.reverse_seconds
            elif not targets:
                self.reason = 'TARGET NOT VISIBLE'
            elif len(targets) != 1:
                self.reason = 'MULTIPLE TARGETS OF SAME CLASS'
            elif not marker:
                self.reason = 'MARKER NOT VISIBLE'
            elif marker.get('skew', 1) > .5:
                self.reason = 'MARKER TOO SKEWED'
            elif now >= self.settle_until:
                x1, _, x2, _ = targets[0]['bbox']
                bearing = ((x1+x2)/2 / observation['frame_width'])*2-1
                # First centre the object, then use the marker direction while pushing.
                steering = bearing if abs(bearing)>cfg.bearing_deadband else marker['bearing']
                if abs(steering)>cfg.bearing_deadband:
                    if not self.pulse_until: self.pulse_until = now+cfg.turn_pulse_seconds
                    if now < self.pulse_until:
                        command = 'R' if steering>0 else 'L'
                    else:
                        self.pulse_until = 0; self.settle_until = now+cfg.settle_seconds
                else:
                    self.pulse_until = 0; command = 'F'
        elif self.phase in ('BACKING', 'TURNING_AROUND'):
            desired = 'B' if self.phase == 'BACKING' else 'R'
            # Count only periods with fresh ACKs, not disconnected wall time.
            if self.last_output == desired and ack == desired and now-motor.get('acknowledged_at', 0)<.25:
                self.timed_remaining -= dt
            if self.timed_remaining <= 0:
                if self.phase == 'BACKING':
                    self.phase, self.timed_remaining = 'TURNING_AROUND', cfg.turnaround_seconds
                else:
                    self._finish_motion(now)
                command = 'S'
            else:
                command = desired
        elif self.phase == 'RUNNING':
            command = 'F'
        self.last_output = command
        if command != 'S' or self.phase in ('HAZARD_PAUSED','PAUSED','RECHECKING'):
            self.reason = self.phase
        return command

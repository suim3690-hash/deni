import unittest
from care_controller import CareController, Settings


def obj(label, bearing=0.0):
    centre = (bearing + 1) * 50
    return dict(label=label, bbox=[centre-5, 30, centre+5, 60])


def drop_marker(fill=.01, bearing=0, skew=0, centre=(50,45)):
    return dict(id=0, fill=fill, bearing=bearing, skew=skew, centre=list(centre))


class CareTests(unittest.TestCase):
    def setUp(self):
        self.c = CareController()
        self.now = 10.0
        self.seq = 0
        self.ack = 'S'

    def tick(self, labels=(), *, gap=.1, valid=True, marker=None, connected=True, generation=1, bearing=0.0):
        self.now += gap
        self.seq += 1
        obs = dict(status='ok' if valid else 'blur', frame_stamp=self.now, sequence=self.seq,
                   hazards=[obj(label, bearing) for label in labels], frame_width=100, frame_height=100,
                   markers=[] if marker is None else [marker])
        result = self.c.step(obs, dict(ready=connected, ack=self.ack, generation=generation, acknowledged_at=self.now), self.now)
        self.ack = result
        return result

    def start(self):
        self.c.request('POWER_ON','on',{},self.now)
        self.tick(); self.tick(); self.tick()
        self.assertEqual(self.c.results['on']['status'],'SUCCEEDED')
        self.assertEqual(self.c.phase,'RUNNING')

    def test_power_on_warmup_does_not_cancel_driving_intent_after_eight_seconds(self):
        self.c.request('POWER_ON','on',{},self.now)
        for _ in range(100): self.assertEqual(self.tick(valid=False),'S')
        self.assertEqual(self.c.results['on']['operationState'],'PAUSED')
        self.assertTrue(self.c.powered)
        self.assertEqual(self.c.phase,'RUNNING')
        self.assertEqual(self.tick(),'F')

    def test_hazard_pause_waits_for_explicit_removal_check(self):
        self.start()
        self.tick(['coin'])
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')
        self.assertEqual(self.c.blocked,{'coin'})

        # 물체가 계속 보이는 동안에는 풀리지 않는다.
        for _ in range(40): self.assertEqual(self.tick(['coin']),'S')
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

        # 물체가 사라져도 사용자 요청 없이 재개하거나 차단 이력을 지우지 않는다.
        deadline = self.now + Settings().removal_absence_seconds * 3
        while self.now < deadline: self.tick()
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')
        self.assertEqual(self.c.blocked,{'coin'})
        self.assertEqual(self.tick(),'S')

        self.c.request('RECHECK_HAZARD','remove',dict(hazardId='h1',objectLabel='동전'),self.now)
        for _ in range(30): self.tick()
        self.assertEqual(self.c.results['remove']['status'],'SUCCEEDED')
        self.assertEqual(self.c.phase,'RUNNING')
        self.assertEqual(self.c.blocked,set())

    def test_pending_treatment_waits_while_target_is_visible(self):
        self.start()
        self.tick(['coin'])
        self.c.request('RECHECK_HAZARD','r1',{'hazardId':'h1','objectLabel':'동전'},self.now)
        self.assertEqual(self.c.phase,'RECHECKING')
        deadline = self.now + Settings().removal_absence_seconds * 3
        while self.now < deadline: self.tick(['coin'])
        # 요청 중에도 물체가 보이면 제거 성공으로 처리하지 않는다.
        self.assertEqual(self.c.phase,'RECHECKING')
        self.assertNotIn('r1', self.c.results)

    def test_removal_redetection_before_completion_keeps_request_pending(self):
        self.start(); self.tick(['battery'])
        self.c.request('RECHECK_HAZARD','remove',dict(hazardId='h1',objectLabel='배터리'),self.now)
        for _ in range(30):
            self.tick()
            if self.c.finishing: break
        self.assertTrue(self.c.finishing)
        self.assertEqual(self.tick(['battery']),'S')
        self.assertNotIn('remove',self.c.results)
        self.assertEqual(self.c.phase,'RECHECKING')

    def test_one_removed_object_does_not_claim_the_remaining_object_is_removed(self):
        self.start(); self.tick(['battery','coin'])
        self.c.request('RECHECK_HAZARD','remove',dict(hazardId='h1',objectLabel='배터리'),self.now)
        for _ in range(30): self.assertEqual(self.tick(['coin']),'S')
        self.assertEqual(self.c.results['remove']['operationState'],'PAUSED')
        self.assertEqual(self.c.blocked,{'coin'})

    def test_off_boot_and_living_hazards_do_not_stop_driving(self):
        self.assertEqual(self.tick(), 'S')
        self.start()
        self.assertEqual(self.tick(['wire','socket']), 'F')
        self.assertEqual(self.tick(['coin']), 'S')
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')
        self.assertEqual(self.tick(), 'S')  # disappearance alone does not clear it

    def test_direct_removal_requires_valid_continuous_two_seconds_and_ack(self):
        self.start(); self.tick(['coin'])
        self.c.request('RECHECK_HAZARD','remove',dict(hazardId='h1',objectLabel='동전'),self.now)
        for _ in range(15): self.tick()
        self.assertNotIn('remove',self.c.results)
        self.tick(valid=False)
        for _ in range(19): self.tick()
        self.assertNotIn('remove',self.c.results)
        for _ in range(7): self.tick()
        result=self.c.results['remove']
        self.assertFalse(result['hazardPresent'])
        self.assertEqual(result['operationState'],'RUNNING')
        self.assertEqual(self.c.phase,'RUNNING')

    def test_disconnection_preserves_task_but_not_absence_window(self):
        self.start(); self.tick(['battery'])
        self.c.request('RECHECK_HAZARD','remove',dict(hazardId='h1',objectLabel='배터리'),self.now)
        for _ in range(18): self.tick()
        self.assertEqual(self.tick(connected=False), 'S')
        self.tick(generation=2)
        for _ in range(18): self.tick(generation=2)
        self.assertNotIn('remove',self.c.results)
        for _ in range(8): self.tick(generation=2)
        self.assertEqual(self.c.results['remove']['status'],'SUCCEEDED')

    def test_power_off_interrupts_treatment_and_stays_off_after_reconnect(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.c.request('POWER_OFF','off',{},self.now)
        self.tick(); self.tick()
        self.assertEqual(self.c.results['move']['status'],'FAILED')
        self.assertEqual(self.c.results['off']['status'],'SUCCEEDED')
        self.assertFalse(self.c.powered)
        self.assertEqual(self.tick(generation=2),'S')

    def test_large_bearing_turns_without_stopping_between_frames(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        # 오차가 coarse_bearing 이상이면 멈춤 없이 계속 돈다.
        commands = [self.tick(['dice'], bearing=-.8) for _ in range(6)]
        self.assertEqual(commands, ['L'] * 6)
        self.assertEqual(self.c.phase,'ALIGNING_TARGET')

    def test_small_bearing_still_pulses_so_it_does_not_overshoot(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        # 데드밴드와 coarse_bearing 사이에서는 짧게 돌고 새 프레임을 기다린다.
        commands = [self.tick(['dice'], bearing=.2) for _ in range(6)]
        self.assertIn('R', commands)
        self.assertIn('S', commands)

    def test_blind_marker_search_keeps_stopping_to_look(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.tick(['dice'])
        while self.c.phase == 'CAPTURING': self.tick([])
        self.assertEqual(self.c.phase,'SEEKING_MARKER')
        # 마커가 보이지 않는 탐색은 흔들린 프레임으로 지나칠 수 있어 펄스를 유지한다.
        commands = [self.tick([]) for _ in range(6)]
        self.assertIn('S', commands)
        self.assertIn('R', commands)

    def test_relocation_captures_then_uses_marker_and_verifies_drop(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.assertEqual(self.tick(['dice']),'F')
        self.assertEqual(self.c.phase,'CAPTURING')
        # The target can disappear below the camera after entering the arm.
        while self.c.phase == 'CAPTURING': self.tick([])
        self.assertEqual(self.c.phase,'SEEKING_MARKER')
        self.assertEqual(self.tick([],marker=drop_marker(bearing=.5)),'R')
        self.assertEqual(self.tick([],marker=drop_marker()),'F')
        self.assertEqual(self.c.phase,'PUSHING_TO_MARKER')
        self.assertEqual(self.tick([],marker=drop_marker(fill=.14)),'S')
        self.assertEqual(self.c.phase,'BACKING')
        while self.c.phase == 'BACKING': self.tick([],marker=drop_marker(fill=.14))
        self.assertEqual(self.c.phase,'VERIFYING_DROP')
        for _ in range(20):
            self.tick(['dice'],marker=drop_marker(fill=.08))
            if self.c.phase == 'TURNING_AROUND': break
        self.assertEqual(self.c.phase,'TURNING_AROUND')
        for _ in range(40):
            self.tick()
            if 'move' in self.c.results: break
        self.assertEqual(self.c.results['move']['status'],'SUCCEEDED')
        self.assertTrue(self.c.results['move']['relocationCompleted'])
        self.assertEqual(self.c.phase,'RUNNING')

    def test_relocation_allows_other_hazards_but_leaves_them_unresolved(self):
        self.start(); self.tick(['dice', 'coin'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.assertEqual(self.tick(['dice','coin']),'F')
        while self.c.phase == 'CAPTURING': self.tick(['coin'])
        self.tick(['coin'],marker=drop_marker())
        self.tick(['coin'],marker=drop_marker(fill=.14))
        commands=[]
        while self.c.phase == 'BACKING': commands.append(self.tick(['coin'],marker=drop_marker(fill=.14)))
        for _ in range(20):
            commands.append(self.tick(['dice','coin'],marker=drop_marker(fill=.08)))
            if self.c.phase == 'TURNING_AROUND': break
        for _ in range(40):
            commands.append(self.tick(['coin']))
            if 'move' in self.c.results: break
        self.assertIn('B',commands)
        self.assertIn('R',commands)
        self.assertEqual(self.c.results['move']['status'],'SUCCEEDED')
        self.assertEqual(self.c.results['move']['operationState'],'PAUSED')
        self.assertEqual(self.c.blocked,{'coin'})
        self.assertEqual(self.tick(['coin']),'S')

    def test_relocation_requires_one_target_before_capture_and_at_drop(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.assertEqual(self.tick([],marker=drop_marker()),'S')
        self.assertEqual(self.c.phase,'ALIGNING_TARGET')
        self.assertEqual(self.c.reason,'TARGET NOT VISIBLE')
        self.assertEqual(self.tick(['dice','dice'],marker=drop_marker()),'S')
        self.assertEqual(self.c.phase,'ALIGNING_TARGET')
        self.assertEqual(self.c.reason,'MULTIPLE TARGETS OF SAME CLASS')
        self.assertEqual(self.tick(['dice']),'F')
        while self.c.phase == 'CAPTURING': self.tick([])
        self.tick([],marker=drop_marker())
        self.tick([],marker=drop_marker(fill=.14))
        while self.c.phase == 'BACKING': self.tick([],marker=drop_marker(fill=.14))
        # Seeing only the marker does not prove the object was delivered.
        for _ in range(110):
            self.tick([],marker=drop_marker(fill=.08))
            if 'move' in self.c.results: break
        self.assertEqual(self.c.results['move']['status'],'FAILED')
        self.assertEqual(self.c.results['move']['errorCode'],'DROP_NOT_VERIFIED')
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

    def test_relocation_searches_without_forward_motion_and_fails_without_marker(self):
        self.start(); self.tick(['coin'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='동전'),self.now)
        self.tick(['coin'])
        while self.c.phase == 'CAPTURING': self.tick([])
        commands=[]
        for _ in range(130):
            commands.append(self.tick([]))
            if 'move' in self.c.results: break
        self.assertNotIn('F',commands)
        self.assertIn('R',commands)
        self.assertEqual(self.c.results['move']['status'],'FAILED')
        self.assertEqual(self.c.results['move']['errorCode'],'MARKER_NOT_FOUND')
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

    def test_configuration_validation(self):
        with self.assertRaises(ValueError): Settings(removal_absence_seconds=1)
        with self.assertRaises(ValueError): Settings(turnaround_seconds=float('nan'))


if __name__=='__main__': unittest.main()

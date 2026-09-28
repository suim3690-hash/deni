import unittest
from care_controller import CareController, Settings


def obj(label, bearing=0.0):
    centre = (bearing + 1) * 50
    return dict(label=label, bbox=[centre-5, 30, centre+5, 60], stable=True)


def drop_marker(fill=.01, bearing=0, skew=0, centre=(50,45)):
    return dict(id=0, fill=fill, bearing=bearing, skew=skew, centre=list(centre))


class CareTests(unittest.TestCase):
    def setUp(self):
        self.c = CareController()
        self.now = 10.0
        self.seq = 0
        self.ack = 'S'

    def tick(self, labels=(), *, gap=.1, valid=True, marker=None, connected=True, generation=1, bearing=0.0,
             backend=True, lag=0.0, instance_ids=None):
        self.now += gap
        self.seq += 1
        obs = dict(status='ok' if valid else 'blur', frame_stamp=self.now-lag, sequence=self.seq,
                   hazards=[obj(label, bearing) for label in labels], frame_width=100, frame_height=100,
                   markers=[] if marker is None else [marker])
        if instance_ids is not None:
            for hazard, identity in zip(obs['hazards'], instance_ids):
                hazard['object_instance_id'] = identity
        result = self.c.step(obs, dict(ready=connected, ack=self.ack, generation=generation, acknowledged_at=self.now),
                             self.now, backend)
        self.ack = result
        return result

    def start(self):
        self.c.request('POWER_ON','on',{},self.now)
        self.tick(); self.tick(); self.tick()
        self.assertEqual(self.c.results['on']['status'],'SUCCEEDED')
        self.assertEqual(self.c.phase,'RUNNING')

    def test_selected_instance_removal_leaves_same_label_other_instance_paused(self):
        for label, korean in [('battery', '배터리'), ('coin', '동전'), ('marble', '구슬'), ('dice', '주사위')]:
            with self.subTest(label=label):
                self.setUp(); self.start()
                self.tick([label, label], instance_ids=['a', 'b'])
                self.c.request('RECHECK_HAZARD', 'remove-a', dict(hazardId='ha', objectLabel=korean, objectInstanceId='a'), self.now)
                for _ in range(30):
                    self.assertEqual(self.tick([label], instance_ids=['b']), 'S')
                self.assertEqual(self.c.results['remove-a']['status'], 'SUCCEEDED')
                self.assertFalse(self.c.results['remove-a']['hazardPresent'])
                self.assertEqual(self.c.results['remove-a']['operationState'], 'PAUSED')
                self.assertEqual(self.c.blocked_instances, {'b': label})
                self.c.request('RECHECK_HAZARD', 'remove-b', dict(hazardId='hb', objectLabel=korean, objectInstanceId='b'), self.now)
                for _ in range(30): self.tick()
                self.assertEqual(self.c.results['remove-b']['status'], 'SUCCEEDED')
                self.assertEqual(self.c.blocked_instances, {})
                self.assertEqual(self.c.phase, 'RUNNING')

    def test_selected_instance_remaining_or_unidentified_never_counts_as_removed(self):
        for identity in ['a', None]:
            with self.subTest(identity=identity):
                self.setUp(); self.start()
                self.tick(['battery', 'battery'], instance_ids=['a', 'b'])
                self.c.request('RECHECK_HAZARD', 'remove', dict(hazardId='h', objectLabel='배터리', objectInstanceId='a'), self.now)
                for _ in range(30): self.tick(['battery'], instance_ids=[identity])
                self.assertEqual(self.c.results['remove']['status'], 'FAILED')
                self.assertTrue(self.c.results['remove']['hazardPresent'])

    def test_absent_same_label_other_object_stays_blocked_until_its_own_check(self):
        self.start(); self.tick(['coin', 'coin'], instance_ids=['a', 'b'])
        self.c.request('RECHECK_HAZARD', 'remove', dict(hazardId='h', objectLabel='동전', objectInstanceId='a'), self.now)
        for _ in range(30): self.assertEqual(self.tick(), 'S')
        self.assertEqual(self.c.results['remove']['status'], 'SUCCEEDED')
        self.assertEqual(self.c.blocked_instances, {'b': 'coin'})
        self.assertEqual(self.c.phase, 'HAZARD_PAUSED')

    def test_removal_and_control_deadlines_survive_motor_disconnect(self):
        self.start()
        self.tick(['coin'])
        self.c.request('RECHECK_HAZARD', 'remove', dict(hazardId='h1', objectLabel='동전'), self.now)
        self.assertEqual(self.tick(connected=False, gap=121), 'S')
        self.assertEqual(self.c.results['remove']['errorCode'], 'ACTION_TIMEOUT')
        self.assertEqual(self.c.blocked, {'coin'})
        self.c.request('PAUSE', 'pause', {}, self.now)
        self.assertEqual(self.tick(connected=False, gap=9), 'S')
        self.assertEqual(self.c.results['pause']['errorCode'], 'STATE_NOT_CONFIRMED')
        self.assertIsNone(self.c.control)

    def test_power_on_warmup_does_not_cancel_driving_intent_after_eight_seconds(self):
        self.c.request('POWER_ON','on',{},self.now)
        for _ in range(100): self.assertEqual(self.tick(valid=False),'S')
        self.assertEqual(self.c.results['on']['operationState'],'PAUSED')
        self.assertTrue(self.c.powered)
        self.assertEqual(self.c.phase,'RUNNING')
        self.assertEqual(self.tick(),'F')

    def test_hazard_pause_clears_itself_once_detection_stops_seeing_the_object(self):
        self.start()
        self.tick(['coin'])
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')
        self.assertEqual(self.c.blocked,{'coin'})

        # 물체가 계속 보이는 동안에는 풀리지 않는다.
        for _ in range(40): self.assertEqual(self.tick(['coin']),'S')
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

        # 사라진 직후에는 유지한다. 순간적인 가림으로 목록이 비면 안 된다.
        for _ in range(int(Settings().absent_clear_seconds / .1) - 5):
            self.assertEqual(self.tick(),'S')
        self.assertEqual(self.c.blocked,{'coin'})
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

        # 실시간 인식이 계속 못 보면 사용자 확인 없이 차단과 목록에서 스스로 빠진다.
        for _ in range(10): self.tick()
        self.assertEqual(self.c.blocked,set())
        self.assertEqual(self.c.phase,'RUNNING')
        self.assertEqual(self.tick(),'F')

    def test_absent_instance_clears_itself_while_the_visible_one_stays_blocked(self):
        self.start()
        self.tick(['coin','coin'], instance_ids=['a','b'])
        self.assertEqual(self.c.blocked_instances,{'a':'coin','b':'coin'})
        for _ in range(int(Settings().absent_clear_seconds / .1) + 5):
            self.assertEqual(self.tick(['coin'], instance_ids=['b']),'S')
        self.assertEqual(self.c.blocked_instances,{'b':'coin'})
        # 보고에는 사라진 개체가 빠지고 보이는 개체만 남는다.
        self.assertEqual((self.c.visible_instances,self.c.visible_labels),(['b'],['coin']))
        self.assertTrue(self.c.detection_live(self.now))
        # 같은 종류가 아직 보이므로 라벨 차단과 정지는 유지한다.
        self.assertEqual(self.c.blocked,{'coin'})
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

    def test_observation_gap_is_not_evidence_of_absence(self):
        self.start()
        self.tick(['coin'])
        for _ in range(int(Settings().absent_clear_seconds / .1) + 5):
            self.assertEqual(self.tick(valid=False),'S')
        self.assertEqual(self.c.blocked,{'coin'})
        # 탐지가 멈춘 동안의 빈 목록은 "전부 사라짐"으로 보고되지 않는다.
        self.assertFalse(self.c.detection_live(self.now))
        # 공백 뒤 첫 유효 프레임은 그동안 못 본 시간을 미검출로 세지 않는다.
        self.tick()
        self.assertEqual(self.c.blocked,{'coin'})
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')

    def test_power_off_never_reports_an_empty_scene_as_live(self):
        self.start()
        self.tick(['coin'])
        self.assertTrue(self.c.detection_live(self.now))
        self.c.request('POWER_OFF','off',{},self.now)
        for _ in range(5): self.tick()
        self.assertFalse(self.c.detection_live(self.now))

    def test_relocated_object_is_exempt_only_while_marker_proves_its_safe_position(self):
        self.start()
        self.c.relocated_labels.add('coin')
        self.assertEqual(self.tick(['coin'], marker=drop_marker()), 'F')
        self.assertEqual(self.c.blocked, set())
        self.assertEqual(self.tick(['coin']), 'S')
        self.assertEqual(self.c.blocked, {'coin'})

    def test_same_class_outside_drop_zone_is_not_ignored(self):
        self.start()
        self.c.relocated_labels.add('coin')
        self.assertEqual(self.tick(['coin'], marker=drop_marker(centre=(0, 0))), 'S')
        self.assertEqual(self.c.blocked, {'coin'})

    def test_unconfirmed_candidate_stops_without_leaving_an_unremovable_block(self):
        self.start()
        self.now += .1
        candidate = dict(obj('coin'), stable=False)
        output = self.c.step(dict(status='ok', frame_stamp=self.now, sequence=100,
                                 hazards=[candidate]),
                             dict(ready=True, ack='F', generation=1, acknowledged_at=self.now), self.now)
        self.assertEqual(output, 'S')
        self.assertEqual(self.c.blocked, set())
        self.assertEqual(self.tick(), 'F')

    def test_urgent_candidate_latches_without_waiting_for_votes(self):
        self.start()
        self.now += .1
        candidate = dict(obj('coin'), stable=False, reason='person_and_object')
        output = self.c.step(dict(status='ok', frame_stamp=self.now, sequence=100,
                                 hazards=[candidate]),
                             dict(ready=True, ack='F', generation=1, acknowledged_at=self.now), self.now)
        self.assertEqual(output, 'S')
        self.assertEqual(self.c.blocked, {'coin'})

    def test_recheck_reports_present_instead_of_waiting_for_action_timeout(self):
        self.start()
        self.tick(['coin'])
        self.c.request('RECHECK_HAZARD','r1',{'hazardId':'h1','objectLabel':'동전'},self.now)
        self.assertEqual(self.c.phase,'RECHECKING')
        deadline = self.now + Settings().removal_absence_seconds * 3
        while self.now < deadline: self.tick(['coin'])
        # 요청 중에도 물체가 보이면 제거 성공으로 처리하지 않는다.
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')
        self.assertEqual(self.c.results['r1']['status'], 'FAILED')
        self.assertTrue(self.c.results['r1']['hazardPresent'])
        self.assertEqual(self.c.results['r1']['errorCode'], 'HAZARD_STILL_PRESENT')
        self.assertEqual(self.c.blocked, {'coin'})

    def test_recheck_presence_window_resets_after_blur_and_absence(self):
        self.start(); self.tick(['coin'])
        self.c.request('RECHECK_HAZARD', 'r1', dict(hazardId='h1', objectLabel='동전'), self.now)
        for _ in range(15): self.tick(['coin'])
        self.tick(valid=False)
        for _ in range(15): self.tick(['coin'])
        self.assertNotIn('r1', self.c.results)
        self.tick()
        for _ in range(15): self.tick(['coin'])
        self.assertNotIn('r1', self.c.results)
        for _ in range(10): self.tick(['coin'])
        self.assertTrue(self.c.results['r1']['hazardPresent'])

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

    def resume(self, identity, **tick):
        self.c.request('RESUME', identity, {}, self.now)
        for _ in range(3): self.tick(**tick)
        return self.c.results[identity]

    def test_motor_reconnect_does_not_restart_driving_without_resume(self):
        self.start()
        self.assertEqual(self.tick(), 'F')
        self.assertEqual(self.tick(connected=False), 'S')
        for _ in range(20): self.assertEqual(self.tick(generation=2), 'S')
        self.assertEqual(self.c.phase, 'PAUSED')
        self.assertEqual(self.resume('go', generation=2)['status'], 'SUCCEEDED')
        self.assertEqual(self.tick(generation=2), 'F')

    def test_motor_reconnect_fails_relocation_and_keeps_hazard_blocked(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.tick(['dice'])
        self.tick(connected=False)
        self.assertEqual(self.tick(['dice'], generation=2), 'S')
        self.assertEqual(self.c.results['move']['errorCode'], 'MOTOR_RECONNECTED')
        self.assertEqual(self.c.phase, 'HAZARD_PAUSED')
        self.assertEqual(self.c.blocked, {'dice'})

    def test_backend_loss_stops_and_reconnect_waits_for_resume(self):
        self.start()
        self.assertEqual(self.tick(), 'F')
        for _ in range(20): self.assertEqual(self.tick(backend=False), 'S')
        self.assertEqual(self.c.reason, 'BACKEND DISCONNECTED')
        for _ in range(20): self.assertEqual(self.tick(), 'S')
        self.assertEqual(self.c.phase, 'PAUSED')
        self.assertEqual(self.resume('go')['status'], 'SUCCEEDED')
        self.assertEqual(self.tick(), 'F')

    def test_backend_loss_fails_removal_check_and_keeps_hazard_blocked(self):
        self.start(); self.tick(['coin'])
        self.c.request('RECHECK_HAZARD','remove',dict(hazardId='h1',objectLabel='동전'),self.now)
        for _ in range(5): self.tick()
        self.assertEqual(self.tick(backend=False), 'S')
        self.assertEqual(self.c.results['remove']['errorCode'], 'BACKEND_DISCONNECTED')
        # 물체가 없어도 재연결만으로는 제거 성공이나 재주행으로 이어지지 않는다.
        for _ in range(40): self.assertEqual(self.tick(), 'S')
        self.assertEqual(self.c.phase, 'HAZARD_PAUSED')
        self.assertEqual(self.c.blocked, {'coin'})

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
        for _ in range(int(self.c.settings.turnaround_seconds / .1) + 20):
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
        for _ in range(int(self.c.settings.turnaround_seconds / .1) + 20):
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

    def capture(self, label='coin', korean='동전'):
        self.start(); self.tick([label])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel=korean),self.now)
        self.tick([label])
        while self.c.phase == 'CAPTURING': self.tick([])

    def test_relocation_sweeps_both_ways_and_fails_only_after_turn_budget(self):
        self.capture()
        started = self.now
        commands=[]
        for _ in range(1000):
            commands.append(self.tick([]))
            if 'move' in self.c.results: break
        self.assertNotIn('F',commands)
        # Right first by default, then the other way round to cover the circle.
        self.assertLess(commands.index('R'), commands.index('L'))
        self.assertEqual(self.c.results['move']['errorCode'],'MARKER_NOT_FOUND')
        self.assertEqual(self.c.phase,'HAZARD_PAUSED')
        # Wall time is not the limit: the old 12 s cut-off is well exceeded.
        self.assertGreater(self.now-started, 20)
        cfg = Settings()
        self.assertGreaterEqual(self.c.search_turned, cfg.search_turns*cfg.full_turn_seconds)

    def test_lost_marker_is_searched_toward_the_side_it_was_last_seen(self):
        self.capture()
        self.assertEqual(self.tick([],marker=drop_marker(bearing=-.5)),'L')
        commands = [self.tick([]) for _ in range(120)]
        turns = [command for command in commands if command in 'LR']
        self.assertEqual(turns[0],'L')
        self.assertIn('R',turns)

    def test_one_missed_frame_while_pushing_does_not_turn_away(self):
        self.capture()
        self.assertEqual(self.tick([],marker=drop_marker()),'F')
        self.assertEqual(self.c.phase,'PUSHING_TO_MARKER')
        self.assertEqual(self.tick([]),'S')
        self.assertEqual(self.tick([],marker=drop_marker()),'F')
        self.assertEqual(self.c.phase,'PUSHING_TO_MARKER')

    def test_search_turns_again_only_after_a_frame_taken_while_stopped(self):
        self.capture()
        commands = [self.tick([], lag=.5) for _ in range(40)]
        first_stop = commands.index('R') + commands[commands.index('R'):].index('S')
        waited = commands[first_stop:].index('R')
        # settle 0.35 s plus 0.5 s result latency at 0.1 s per tick.
        self.assertGreaterEqual(waited, 8)

    def test_configuration_validation(self):
        with self.assertRaises(ValueError): Settings(removal_absence_seconds=1)
        with self.assertRaises(ValueError): Settings(turnaround_seconds=float('nan'))
        with self.assertRaises(ValueError): Settings(search_sweep_degrees=180)
        with self.assertRaises(ValueError): Settings(search_turns=.5)

    def test_measured_drop_layout_passes_updated_radius(self):
        target = obj('battery')
        target['bbox'] = [459.46, 341.55, 570.26, 705.18]
        marker = drop_marker(centre=(639.2, 230.5))
        observation = dict(frame_width=1280, frame_height=720)
        self.assertFalse(CareController(Settings(drop_verify_radius_ratio=.3))
                         ._drop_is_verified([target], marker, observation))
        self.assertTrue(self.c._drop_is_verified([target], marker, observation))


if __name__=='__main__': unittest.main()

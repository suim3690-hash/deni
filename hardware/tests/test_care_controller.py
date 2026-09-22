import unittest
from care_controller import CareController, Settings


def obj(label):
    return dict(label=label, bbox=[45,30,55,60])


class CareTests(unittest.TestCase):
    def setUp(self):
        self.c = CareController()
        self.now = 10.0
        self.seq = 0
        self.ack = 'S'

    def tick(self, labels=(), *, gap=.1, valid=True, marker=None, connected=True, generation=1):
        self.now += gap
        self.seq += 1
        obs = dict(status='ok' if valid else 'blur', frame_stamp=self.now, sequence=self.seq,
                   hazards=[obj(label) for label in labels], frame_width=100, markers=[] if marker is None else [marker])
        result = self.c.step(obs, dict(ready=connected, ack=self.ack, generation=generation, acknowledged_at=self.now), self.now)
        self.ack = result
        return result

    def start(self):
        self.c.request('POWER_ON','on',{},self.now)
        self.tick(); self.tick(); self.tick()
        self.assertEqual(self.c.results['on']['operationState'],'RUNNING')

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

    def test_relocation_needs_target_and_marker_then_backs_and_turns(self):
        self.start(); self.tick(['dice'])
        self.c.request('RELOCATE','move',dict(hazardId='h1',objectLabel='주사위'),self.now)
        self.assertEqual(self.tick(['dice']),'S')
        marker=dict(id=0,fill=.01,bearing=0,skew=0)
        self.assertEqual(self.tick(['dice'],marker=marker),'F')
        marker['fill']=.14
        self.assertEqual(self.tick(['dice'],marker=marker),'S')
        self.assertEqual(self.c.phase,'BACKING')
        self.assertEqual(self.tick(['dice'],marker=marker),'B')
        for _ in range(35): self.tick()
        self.assertEqual(self.c.results['move']['status'],'SUCCEEDED')
        self.assertTrue(self.c.results['move']['relocationCompleted'])
        self.assertEqual(self.c.phase,'RUNNING')

    def test_configuration_validation(self):
        with self.assertRaises(ValueError): Settings(removal_absence_seconds=1)
        with self.assertRaises(ValueError): Settings(turnaround_seconds=float('nan'))


if __name__=='__main__': unittest.main()

import threading
import unittest

from care_runtime import Runtime


class Detector:
    def __init__(self):
        self.suppressed = set()
        self.reset = set()

    def set_suppressed_alert_labels(self, labels):
        self.suppressed = set(labels)

    def reset_alert_labels(self, labels):
        self.reset.update(labels)


class RuntimeAlertTests(unittest.TestCase):
    def setUp(self):
        self.detector = Detector()
        self.runtime = Runtime(None, self.detector, threading.Event(), None)
        self.runtime.controller.powered = True
        self.runtime.controller.phase = 'HAZARD_PAUSED'

    def test_only_relocation_target_is_suppressed_until_action_ends(self):
        controller = self.runtime.controller
        controller.request('RELOCATE', 'move',
                           {'hazardId': 'h1', 'objectLabel': '배터리'}, 10.0)
        self.runtime._sync_alert_suppression()
        self.assertEqual(self.detector.suppressed, {'battery'})
        controller._complete_action('FAILED', 'TEST_END')
        self.runtime._sync_alert_suppression()
        self.assertEqual(self.detector.suppressed, set())

    def test_direct_removal_does_not_suppress_alerts(self):
        self.runtime.controller.request('RECHECK_HAZARD', 'remove',
                                        {'hazardId': 'h1', 'objectLabel': '배터리'}, 10.0)
        self.runtime._sync_alert_suppression()
        self.assertEqual(self.detector.suppressed, set())

    def test_successful_direct_removal_resets_target_alert_cooldown(self):
        self.runtime.controller.results['remove'] = dict(
            status='SUCCEEDED', operationState='RUNNING', hazardId='h1', hazardPresent=False)
        result = self.runtime.command('RECHECK_HAZARD', 'remove',
                                      {'hazardId':'h1','objectLabel':'배터리'})
        self.assertEqual(result['status'], 'SUCCEEDED')
        self.assertEqual(self.detector.reset, {'battery'})

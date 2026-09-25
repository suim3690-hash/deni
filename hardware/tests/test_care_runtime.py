import threading
import unittest

from care_runtime import FocusSwitch, Runtime


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

    def test_focus_is_far_only_for_marker_phases_and_retries_failures(self):
        sent, fail = [], [True]
        def post(name):
            if fail[0]:
                fail[0] = False
                raise OSError('old Pi server')
            sent.append(name)
        focus = FocusSwitch('http://pi/focus', 'macro', 'normal', threading.Event(), post)
        self.assertFalse(focus.sync_once())
        self.assertTrue(focus.sync_once())
        focus.want(True); focus.sync_once()
        focus.want(True); focus.sync_once()
        focus.want(False); focus.sync_once()
        self.assertEqual(sent, ['macro', 'normal', 'macro'])

    def test_backend_is_down_until_first_message_and_after_silence(self):
        self.assertFalse(self.runtime.backend_ok(10.0))
        self.runtime.backend_contacted()
        contact = self.runtime.backend_contact
        limit = self.runtime.controller.settings.backend_timeout_seconds
        self.assertTrue(self.runtime.backend_ok(contact + limit))
        self.assertFalse(self.runtime.backend_ok(contact + limit + .1))

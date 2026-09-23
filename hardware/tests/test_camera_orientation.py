import unittest
import threading
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import cv2
import numpy as np

from pc_dashboard import CameraFeed, camera_worker
from detection.markers import DICTIONARY, MarkerDetector


class CameraOrientationTests(unittest.TestCase):
    def test_server_rotated_frame_is_not_rotated_again_before_marker_detection(self):
        frame = np.full((480, 640, 3), 255, dtype=np.uint8)
        marker = cv2.aruco.generateImageMarker(
            cv2.aruco.getPredefinedDictionary(DICTIONARY), 0, 120)
        # This is the already normalized frame received from the Pi server.
        frame[220:340, 460:580] = cv2.cvtColor(marker, cv2.COLOR_GRAY2BGR)
        ok, encoded = cv2.imencode('.jpg', frame)
        self.assertTrue(ok)
        jpeg = encoded.tobytes()
        feed = CameraFeed()
        feed.publish(jpeg)
        received, sequence, stamp, error = feed.snapshot()
        self.assertEqual(received, jpeg)
        decoded = cv2.imdecode(np.frombuffer(received, np.uint8), cv2.IMREAD_COLOR)
        markers = MarkerDetector().detect(decoded)
        self.assertEqual(len(markers), 1)
        self.assertEqual(markers[0]['id'], 0)
        self.assertGreater(markers[0]['bearing'], 0)
        self.assertEqual(sequence, 1)
        self.assertGreater(stamp, 0)
        self.assertIsNone(error)

    def test_camera_worker_uses_stream_without_authorization(self):
        stop = threading.Event()
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value

        def consume_once(_response, _feed, event):
            event.set()

        with patch('pc_dashboard.urllib.request.build_opener', return_value=opener), \
                patch('pc_dashboard.read_mjpeg', side_effect=consume_once):
            camera_worker(SimpleNamespace(host='pi.test', camera_port=8000), CameraFeed(), stop)

        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, 'http://pi.test:8000/stream.mjpg')
        self.assertIsNone(request.get_header('Authorization'))

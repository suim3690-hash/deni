"""Usage: python hardware/examples/upload_detection.py annotated.png 동전 HAZARD
Reuse EVENT_ID on retry; a fresh invocation otherwise creates a new event.
"""
import os
import sys
from datetime import datetime, timezone
from uuid import uuid4
import requests


def main():
    event = os.environ.get("EVENT_ID", str(uuid4()))
    # Without capturedAt the backend keeps the image but never raises a hazard from it.
    captured = os.environ.get("CAPTURED_AT", datetime.now(timezone.utc).isoformat())
    print("EVENT_ID / CAPTURED_AT (retain both for retries):", event, captured)
    with open(sys.argv[1], "rb") as frame:
        response = requests.post(
            os.environ.get("ROBOT_HTTP_URL", "http://localhost:8080") + "/api/v1/hardware/detections",
            headers={"Authorization": "Bearer " + os.environ["ROBOT_DEVICE_TOKEN"],
                     "X-Device-Id": os.environ["ROBOT_DEVICE_ID"]},
            data={"eventId": event, "objectLabel": sys.argv[2], "modelType": sys.argv[3],
                  "capturedAt": captured},
            files={"image": frame}, timeout=20)
    response.raise_for_status()
    print(response.json())


if __name__ == "__main__":
    main()

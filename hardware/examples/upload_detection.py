"""Usage: python hardware/examples/upload_detection.py annotated.png 동전 HAZARD
Reuse EVENT_ID on retry; a fresh invocation otherwise creates a new event.
"""
import os
import sys
from uuid import uuid4
import requests


def main():
    event = os.environ.get("EVENT_ID", str(uuid4()))
    print("EVENT_ID (retain for retries):", event)
    with open(sys.argv[1], "rb") as frame:
        response = requests.post(
            os.environ.get("ROBOT_HTTP_URL", "http://localhost:8080") + "/api/v1/hardware/detections",
            headers={"Authorization": "Bearer " + os.environ["ROBOT_DEVICE_TOKEN"],
                     "X-Device-Id": os.environ["ROBOT_DEVICE_ID"]},
            data={"eventId": event, "objectLabel": sys.argv[2], "modelType": sys.argv[3]},
            files={"image": frame}, timeout=20)
    response.raise_for_status()
    print(response.json())


if __name__ == "__main__":
    main()

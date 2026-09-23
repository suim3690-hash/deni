"""Development simulator only: never controls a real motor. python hardware/simulator.py"""
import asyncio
import json
import os
from datetime import datetime, timezone
from uuid import uuid4
from websockets.asyncio.client import connect


def now():
    return datetime.now(timezone.utc).isoformat()


async def main():
    device = os.environ["ROBOT_DEVICE_ID"]
    token = os.environ["ROBOT_DEVICE_TOKEN"]
    url = os.environ.get("ROBOT_WS_URL", "ws://localhost:8080/ws/devices")
    operation = "RUNNING"
    results = {}  # Real hardware must persist command IDs/results across restarts.

    async def send(socket, kind, payload):
        await socket.send(json.dumps(dict(type=kind, messageId=str(uuid4()), deviceId=device, sentAt=now(), payload=payload)))

    async def report(socket):
        while True:
            await send(socket, "ROBOT_STATE", dict(operationState=operation,
                       movementState="STOPPED" if operation == "PAUSED" else "FORWARD",
                       sampledAt=now(), batteryPercent=80,
                       movementDurationMs=None, movementDistanceM=None))
            await asyncio.sleep(1)

    async with connect(url, additional_headers={"Authorization": "Bearer " + token, "X-Device-Id": device}) as socket:
        print("Simulator connected:", device)
        task = asyncio.create_task(report(socket))
        try:
            async for raw in socket:
                message = json.loads(raw)
                if message.get("type") != "COMMAND":
                    if message.get("type") == "ERROR":
                        print("Server rejected message:", message)
                    continue
                payload = message["payload"]
                command = payload["commandId"]
                if command in results:
                    await send(socket, "COMMAND_RESULT", results[command])
                    continue
                if datetime.fromisoformat(payload["expiresAt"]) <= datetime.now(timezone.utc):
                    print("Expired command ignored:", command)
                    continue
                await send(socket, "COMMAND_ACK", dict(commandId=command, status="DELIVERED"))
                supported = payload["command"] == "PAUSE"
                if supported:
                    operation = "PAUSED"
                result = dict(commandId=command, status="SUCCEEDED" if supported else "FAILED",
                              operationState=operation, completedAt=now(), errorCode=None if supported else "UNSUPPORTED_COMMAND")
                results[command] = result
                await send(socket, "COMMAND_RESULT", result)
                print("Simulated result:", result)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)


if __name__ == "__main__":
    asyncio.run(main())

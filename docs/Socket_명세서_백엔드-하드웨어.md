# 백엔드–하드웨어 연결 (2026-09-20)

현재 구현은 등록 기기 **1대·백엔드 인스턴스 1개** 기준이다. 실제 모터 코드는 하드웨어 담당자가 연결한다.

```text
프론트 ── HTTP 조회/요청 ── 백엔드 ── WebSocket 상태/PAUSE ── 하드웨어
                              │       ← HTTP 이미지 업로드 ── 모델
                          PostgreSQL
```

## 연결 설정

| 항목 | 계약 |
| --- | --- |
| WebSocket | `ws://백엔드IP:8080/ws/devices` |
| HTTP 업로드 | `POST http://백엔드IP:8080/api/v1/hardware/detections` |
| 공통 헤더 | `Authorization: Bearer {키}`, `X-Device-Id: {등록된 기기 ID}` |
| 백엔드 환경변수 | `ROBOT_DEVICE_ID`, `ROBOT_DEVICE_TOKEN` (최소 32자, 별도 공유) |
| 연결 제한 | 동일 기기 중복 연결 거부. 키 미설정·잘못된 키는 인증 실패 |
| 운영 범위 | 개발망 전용. 보호자 인증·권한, TLS, 다중 기기는 후속 작업 |

기기는 먼저 `POST /api/v1/devices`로 실제 아이에 등록한다. Socket 연결만으로 온라인이 되지 않고 유효한 상태 보고가 필요하다.
기존 기기 상태 유효기간 기본값은 300초이며 개발 시 `DEVICE_STATUS_MAX_AGE_SECONDS=10` 설정을 권장한다.
`/robot-state`는 측정·수신 후 10초가 지나면 미확인이다. Socket 종료 또는 최신 이동 상태가 `UNKNOWN`이면 commandsAvailable=false가 되고,
연결 상태는 마지막 보고의 유효기간을 따른다.

## 메시지

모든 기기 메시지는 `type`, UUID `messageId`, `deviceId`, 시간대 포함 `sentAt`, `payload`를 보낸다.
성공 응답은 `{"type":"RECEIPT","messageId":"...","accepted":true}`이다.
오류 응답은 `{"type":"ERROR","code":"INVALID_MESSAGE"}`이다.

| 방향 | type | payload |
| --- | --- | --- |
| 기기 → 백 | `ROBOT_STATE` | `operationState`, `movementState`, `sampledAt`, 선택적 `batteryPercent`, `movementDurationMs`, `movementDistanceM` |
| 백 → 기기 | `COMMAND` | `commandId`, `command:"PAUSE"`, `expiresAt`, `parameters:{}` |
| 기기 → 백 | `COMMAND_ACK` | `commandId`, `status:"DELIVERED"` |
| 기기 → 백 | `COMMAND_RESULT` | `commandId`, `status:"SUCCEEDED"` 또는 `"FAILED"`, `operationState`, `completedAt`, 선택적 `errorCode` |

상태 전송 예시 (실제 현재 시각으로 바꿔 전송):

```json
{
  "type": "ROBOT_STATE",
  "messageId": "8dc780e3-50ee-4c53-82eb-87f5b49edbd2",
  "deviceId": "robot-001",
  "sentAt": "2026-09-20T07:00:00Z",
  "payload": {
    "operationState": "RUNNING", "movementState": "FORWARD",
    "sampledAt": "2026-09-20T07:00:00Z", "batteryPercent": 80,
    "movementDurationMs": 1200, "movementDistanceM": 0.35
  }
}
```

| 필드 | 허용값 |
| --- | --- |
| 동작 | RUNNING, PAUSED, RELOCATING, UNKNOWN |
| 이동 | FORWARD, TURNING, BACKWARD, STOPPED, UNKNOWN |
| 시간·거리 | 현재 구간 기준 ms·m, 미측정은 null |
| 배터리 | 0~100 또는 null |

RELOCATING은 원본 상태에 보관하지만 기존 devices.operation_state에는 UNKNOWN으로 표시한다.
상태는 1초 간격으로 보고한다. 과거 상태는 무시하고 동일 측정 시각에 다른 내용은 거부한다.
별도 HEARTBEAT나 Socket DETECTION은 아직 지원하지 않는다.

## 일시정지 흐름

| 단계 | 동작 |
| --- | --- |
| 1 | HTTP `POST /api/v1/devices/{id}/commands/pause`, `{}`, UUID `Idempotency-Key` |
| 2 | 최근 온라인 상태와 Socket 연결 시 같은 트랜잭션에서 접수·전달 대기 저장 |
| 3 | 백엔드가 0.5초 간격으로 커밋된 대기 명령 전송, 기기는 ACK 후 실제 정지 |
| 4 | 실제 정지 후 같은 commandId로 SUCCEEDED·PAUSED·completedAt 전송 |
| 5 | `GET /api/v1/devices/{id}/commands/{commandId}`로 결과 확인 |

전달 상태는 QUEUED → SENT → DELIVERED → SUCCEEDED/FAILED다.
HTTP status는 대기·전달 중 REQUESTED, 최종은 SUCCEEDED/FAILED/EXPIRED/UNKNOWN이다.
요청 후 10초 동안 보내지 못하면 EXPIRED, 보냈으나 결과가 없으면 UNKNOWN이다.
늦은 실제 결과는 UNKNOWN에서 확정 가능하다. 자동 재전송하지 않는다. 같은 HTTP 키는 새 명령을 만들지 않는다.
하드웨어는 commandId와 결과를 영속 저장해 중복 동작을 막고, 만료 명령은 실행하지 않는다.
서버·기기 시계를 동기화하고 completedAt은 실제 완료 시각을 유지한다.
PAUSED 상태 보고만으로 명령 성공을 추정하지 않는다. 결과 보고와 별도로 최신 ROBOT_STATE를 계속 전송한다.

이전 접수 기록과 Socket 없이 기록한 요청은 NOT_CONNECTED이며 나중에 자동 실행되지 않는다.
commandsAvailable은 활성 Socket과 최신 온라인 보고가 있고, 모터가 10초 이내 보고한 이동 상태가 `UNKNOWN`이 아닐 때의 PAUSE 전달 가능 여부다. 제어 상태 유효기간은 `ROBOT_CONTROL_STATUS_MAX_AGE_SECONDS`로 조정하며 재개·이송 지원을 뜻하지 않는다.
현재 홈의 전원/ThinQ 버튼은 PAUSE 버튼이 아니다. HTTP 경로는 준비됐지만 화면 버튼 연결은 프론트 담당자가 해야 한다.

## 탐지 이미지 업로드

multipart 필드: `eventId`(UUID), `modelType`(HAZARD/OBJECT), `objectLabel`, `image`(바운딩 박스 JPEG/PNG, 최대 5MiB).
응답: `200 {"eventId":"...","hazardId":"... 또는 null"}`.
재전송은 같은 ID·모델·라벨·이미지 사용. 다른 내용 재사용은 409다.
detected_at은 DB 수신 시각, 이미지 URL은 `/api/v1/devices/{id}/detections/{eventId}/image`다.

| 한글 라벨에 포함된 문자열 | 분류 | 0~11개월 | 12~35개월 | 36~95개월 |
| --- | --- | --- | --- | --- |
| 구슬·동전·배터리 | SWALLOW | VERY_HIGH | HIGH | MEDIUM |
| 전선·콘센트 | LIVING | HIGH | HIGH | VERY_HIGH |

현재 프론트 표시 규칙을 맞춘 개발 정책이다. 두 모델 모두 라벨 기준으로 분류하며 생활공간 분류가 우선한다.
다른 라벨·지원 연령 밖은 원본만 저장하고 hazardId=null이다. 영어 라벨 매핑은 별도 확정한다.
기존 직접 DB 저장 이벤트는 자동 변환하지 않는다. 위험 자동 정지·재검사·재개·이송은 아직 미연결이다.

## 하드웨어 실행 예제

```powershell
py -m pip install -r hardware/requirements.txt
$env:ROBOT_DEVICE_ID = 'robot-001'
# ROBOT_DEVICE_TOKEN은 별도 전달받아 환경변수로 설정
$env:ROBOT_WS_URL = 'ws://백엔드IP:8080/ws/devices'
py hardware/simulator.py
```

```powershell
$env:ROBOT_HTTP_URL = 'http://백엔드IP:8080'
py hardware/examples/upload_detection.py annotated.png 동전 HAZARD
```

시뮬레이터는 가짜 상태만 변경한다. 실제 하드웨어는 모터 제어·재연결·commandId/결과 영속 저장을 구현한다.
시뮬레이터는 자동 재연결하지 않는다. 업로드 실패 시 출력된 EVENT_ID를 환경변수에 설정하고 같은 파일로 재시도한다.

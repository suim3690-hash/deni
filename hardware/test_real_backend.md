# 실제 백엔드 연동 테스트 절차

모의 수신 서버(`mock_backend.py`)가 아닌 **실제 Spring 백엔드**로 연결해 확인하는 절차다.
모의 서버 기준 검증은 `TEST_CONNECTION_KO.md`에 있고, 이 문서는 그다음 단계다.

전제: 하드웨어 측 코드는 수정하지 않는다. 바꾸는 것은 주소·기기 ID·인증 키 네 개뿐이다.

## 0. 현재까지 검증된 것과 아닌 것

| 항목 | 상태 |
|---|---|
| 실제 Pi 카메라 추론 → 탐지 → HTTP 전송 | 검증 완료 (수신은 모의 서버) |
| WebSocket 상태 송신 / 명령 수신 | 검증 완료 (수신은 모의 서버) |
| 카메라·추론·HTTP·WS 동시 실행 | 검증 완료 |
| **실제 Spring 백엔드 수신** | **미검증 — 이 문서의 목적** |
| 실제 모터 PAUSE 성공 응답 | 미검증 — Arduino 필요 |

## 1. 백엔드 담당자와 먼저 맞출 것

아래가 준비되지 않으면 전부 401 또는 연결 거부로 끝난다.

| 항목 | 확인 내용 |
|---|---|
| 백엔드 주소 | 예: `http://192.168.219.188:8080`. PC 대시보드가 8081을 쓰므로 8080 충돌 없음 |
| `ROBOT_DEVICE_ID` | **백엔드와 하드웨어가 같은 값**이어야 한다 |
| `ROBOT_DEVICE_TOKEN` | **32자 이상**. 미만이면 백엔드가 무조건 401 |
| 기기 등록 | `devices` 테이블에 해당 ID가 있어야 한다 |
| 아이 연결 | 기기에 아이가 연결되어야 한다. 미연결 시 탐지 저장이 실패한다 |
| 아이 성장단계 | `stage`가 없으면 위험물이 생성되지 않고 `hazardId=null`로만 저장된다 |
| `commandsAvailable` | `true`여야 제어 명령이 기기까지 내려온다 |

주의: 백엔드도 `ROBOT_DEVICE_ID` / `ROBOT_DEVICE_TOKEN`이라는 **같은 이름의 환경변수**를 읽는다
(`application.properties`). 같은 PC에서 백엔드와 하드웨어를 함께 띄우면 한 번만 설정해도 양쪽에 적용된다.
값이 서로 다르면 인증이 통과하지 않는다.

## 2. 하드웨어 측 환경변수

```powershell
$env:ROBOT_DEVICE_ID = '<등록된 기기 ID>'
$env:ROBOT_DEVICE_TOKEN = '<32자 이상 공유 키>'
$env:ROBOT_HTTP_URL = 'http://<백엔드 주소>:8080'
$env:ROBOT_WS_URL = 'ws://<백엔드 주소>:8080/ws/devices'
$env:DETECTION_BLUR_THRESHOLD = '<정한 값>'
```

`ROBOT_WS_URL`을 빼면 기본값 `ws://localhost:8080/ws/devices`를 쓴다.
백엔드가 다른 PC에 있으면 반드시 지정한다.

흐림 기준값은 아직 미정이다. 기본값 60은 이 카메라에서 선명한 프레임까지 전량 폐기하므로
그대로 쓰지 않는다. 미정이면 우선 `5.0`으로 두고 진행한다.

## 3. 실행

비밀번호가 없는 통합 Pi 카메라·모터 서버를 먼저 띄운다.

```powershell
cd "C:\Users\Admin\OneDrive\잡것들\DX_Project\3_DX\deni\hardware"
python pc_dashboard.py --host 192.168.219.145 --port 8081 --detection-mode object
```

**pc_dashboard는 한 번만 띄운다.** 백엔드는 기기 하나당 WebSocket 연결 하나만 허용하며,
두 번째 연결은 바로 끊는다.

## 4. 단계별 확인

### 4-1. WebSocket 연결과 인증

실행 로그에 다음이 나오면 핸드셰이크 성공이다.

```
INFO WebSocket connected; motor adapter unavailable
```

이어서 상태 접수 증거가 나와야 한다. **연결 성공만으로 접수 성공으로 보지 않는다.**

```
INFO WS RECEIPT accepted=true messageId=...
```

### 4-2. 로봇 상태 저장 확인

백엔드 담당자가 DB에서 확인한다. 상태 변화 시와 약 1초 주기로 갱신된다.

```sql
SELECT device_id, operation_state, movement_state, sampled_at, received_at
FROM robot_live_state WHERE device_id = '<기기 ID>';
```

Arduino가 없으므로 `movement_state`는 `UNKNOWN`, `operation_state`도 `UNKNOWN`이 정상이다.
`movement_duration_ms`·`movement_distance_m`·배터리는 측정하지 않으므로 NULL이 정상이다.
**측정하지 않은 값을 채워 보내지 않는다.**

### 4-3. 탐지 이미지 전송

카메라 앞 바닥에 구슬·배터리·동전 중 하나를 놓는다. 약 2초간 고정되어야 이벤트가 생성된다.

실행 로그에서:

```
INFO Event <uuid>: sent (HTTP 200)
```

`sent`가 아니라 `rejected`가 나오면 접수 실패다. 응답 본문이 함께 기록되므로 그것으로 원인을 본다.

DB 확인:

```sql
SELECT event_id, device_id, model_type, object_label, image_content_type,
       octet_length(frame_image) AS bytes, detected_at
FROM detection_events WHERE device_id = '<기기 ID>' ORDER BY detected_at DESC LIMIT 10;
```

### 4-4. 위험물 연결 확인

라벨이 아래에 해당하면 위험물이 생성되고 응답의 `hazardId`가 채워진다.

| 라벨 | 분류 | 결과 |
|---|---|---|
| 구슬 · 동전 · 배터리 | SWALLOW | 위험물 생성 |
| 전선 · 콘센트 | LIVING | 위험물 생성 |
| 칼 · 가위 | 해당 없음 | 저장만 되고 `hazardId=null` |

아이의 성장단계가 설정되지 않은 경우에도 `hazardId=null`이다.
**`hazardId=null`은 통신 실패가 아니다.** 저장 성공과 위험 분류는 별개다.

```sql
SELECT id, device_id, source_event_id, category, risk_level, object_label
FROM hazards WHERE device_id = '<기기 ID>' ORDER BY id DESC LIMIT 10;
```

### 4-5. 프론트엔드 제어 명령

프론트엔드에서 일시정지를 요청한다. 프론트는 `VITE_API_BASE_URL`이 설정되어야 실제 API를 쓴다.
없으면 mock 데이터로 동작하므로 연동 확인이 되지 않는다.
CORS는 현재 `localhost:5173` / `127.0.0.1:5173`만 허용한다.

하드웨어 실행 로그에서 순서를 확인한다. 명령 만료는 10초다.

| 상황 | 기대 결과 |
|---|---|
| Arduino 없음 (내일 기본) | `FAILED` / `HARDWARE_NOT_CONNECTED` |
| 모터가 정지 확인 | `SUCCEEDED` / `operationState=PAUSED` |
| 3초 내 정지 미확인 | `FAILED` / `STOP_NOT_CONFIRMED` |

**`HARDWARE_NOT_CONNECTED`는 정상 동작이다.** Arduino가 없는 상태의 정직한 응답이며 버그가 아니다.
백엔드는 `operationState=PAUSED`가 아닌 `SUCCEEDED`를 거부하므로, 정지를 확인하기 전에는
성공으로 보고하지 않는다.

재개(RESUME)는 백엔드가 접수 단계에서 막으므로 기기까지 오지 않는다. 시험 대상이 아니다.

```sql
SELECT command_id, status, sent_at, completed_at, error_code
FROM device_command_delivery WHERE device_id = '<기기 ID>' ORDER BY sent_at DESC LIMIT 10;
```

## 5. 실패 진단

| 증상 | 원인 |
|---|---|
| HTTP 401 | 토큰 불일치, 32자 미만, 또는 백엔드의 `robot.device-id`와 다른 기기 ID |
| HTTP 404 | 주소 오류. 경로는 `/api/v1/hardware/detections` |
| HTTP 400 | 라벨·모델 종류·이미지 문제. 이미지는 서버에서 실제로 디코딩되어야 통과한다 |
| HTTP 409 | 같은 `eventId`에 다른 내용. 재시도는 원본 그대로 보내야 한다 |
| HTTP 413 | 이미지 5MiB 초과 |
| HTTP 5xx / 408 / 429 | 대기열에 남기고 재시도한다. 이벤트를 새로 만들지 않는다 |
| WS 핸드셰이크 401 | 토큰 또는 기기 ID 불일치 |
| WS 연결 직후 종료(1008) | 기기 미등록, 또는 **이미 다른 연결이 있음** |
| `{"type":"ERROR","code":"INVALID_MESSAGE"}` | 계약 밖 메시지. 소켓은 유지되지만 데이터는 버려진다 |
| 탐지 저장 실패 | 기기에 아이가 연결되지 않음 |

전송 실패는 재시도로 처리하며, **재시도할 때 촬영 시각을 현재 시각으로 바꾸지 않는다.**
같은 이벤트는 같은 `eventId`로 보내 중복 저장·중복 알림을 막는다.

## 6. 성공 기준

- [ ] WebSocket 연결 후 `RECEIPT accepted=true` 수신
- [ ] `robot_live_state`에 해당 기기 행이 생기고 `sampled_at`이 갱신됨
- [ ] 실제 탐지 이벤트가 `sent (HTTP 200)`으로 기록됨
- [ ] `detection_events`에 이미지와 라벨이 저장됨
- [ ] 삼킴/생활 위험 라벨에서 `hazards` 행이 생성됨
- [ ] 프론트엔드 화면에서 해당 위험 알림이 보임
- [ ] 같은 이벤트를 다시 보내도 중복 생성되지 않음
- [ ] 프론트 일시정지 요청이 기기까지 도달하고 결과가 회신됨

## 7. 하지 말 것

- 카메라 접속 성공을 모터 ONLINE이나 주행 성공으로 표시하지 않는다.
- 측정하지 않은 거리·시간·배터리를 추정값으로 채우지 않는다.
- 통신 실패를 성공으로 표시하지 않는다.
- 하드웨어 프로그램이 DB 비밀번호를 갖지 않는다. 저장은 백엔드 API를 통해서만 한다.
- 연결이 안 된다고 클라이언트 코드를 먼저 고치지 않는다. 위 표로 원인을 먼저 특정한다.

## 8. 참고

- 모의 서버 기준 절차: `TEST_CONNECTION_KO.md`
- 전체 경위와 결정 사항: `../../HW-backend_connect.md`
- 자동 테스트: `python -m unittest discover -s . -p test_bridge.py` 및 `python -m unittest discover -s tests`

# 통합 실행: 전원·탐지·위험물 처리 (2026-09-22)

프론트 전원 버튼부터 Pi 모터까지 한 경로로 연결한 실행 안내다. 실행 파일은 `care_runtime.py`이며
카메라 수신, 탐지, 백엔드 통신, 모터 소유권을 한 프로세스에서 함께 가진다.
`pc_dashboard.py`나 `robot_push_test/preview.py`를 같이 실행하면 모터 소유자가 둘이 되므로 함께 켜지 않는다.

## 1. 연결된 동작

- 프론트 전원 버튼 → 백엔드 `power-on` / `power-off` → PC 런타임 → Pi → Arduino.
- **전원 ON**: 자동 전진과 위험물 탐지를 시작한다.
- **전원 OFF**: 모터와 탐지를 멈추고 백엔드 통신은 유지한다. 상태 보고와 명령 수신은 계속된다.
- 삼킴 위험물(동전·구슬·배터리·주사위)을 보면 스스로 정지한다. 작업 단계는 `HAZARD_PAUSED`가 된다.
- 정지 후 물체가 화면에서 사라져도 자동 재개하지 않는다. 보호자가 처리 방법을 선택할 때까지 모터는 멈춘다.
  모델 추론은 여러 물체의 알림 사진을 빠뜨리지 않도록 계속하며, 직접 제거 재확인 요청을 받으면 그때부터 대상의 연속 미검출 2초를 센다.
- 생활 위험 요소(전선·콘센트)는 알림만 보내고 주행을 멈추지 않는다.
- 정지 상태에서 프론트가 두 가지를 요청할 수 있다.
  - **직접 제거 재확인**(`RECHECK_HAZARD`): 유효한 영상에서 대상이 2초 연속 보이지 않으면 완료로 보고한다.
  - **안전 이송**(`RELOCATE`): 대상이 정확히 한 개일 때 중앙 정렬 후 일정 시간 전진해 반원형 팔에 확보한다. 이후 물체가 카메라에서 사라져도 마커만 따라 밀고,
    마커 도착 후 후진한다. 정지 화면에서 대상 물체가 마커 주변에 1초 연속 보일 때만 회전·완료 보고한다.
- 조향은 오차 크기에 따라 나뉜다. `coarse_bearing` 밖에서는 멈추지 않고 연속 회전해 움직임이 끊기지 않고,
  그 안쪽에서는 짧은 펄스와 정지로 과회전을 막는다. 마커가 보이지 않는 탐색만은 흔들린 프레임으로 지나치지 않도록 펄스를 유지한다.
- 완료 보고를 받은 위험물만 백엔드에서 해결 처리된다. 프론트는 기기 보고 전에는 완료로 표시하지 않는다.

## 2. 실행 순서

1. **Pi**: 서버를 수동으로 실행한다. 부팅 자동 실행은 적용하지 않았다.

   ```bash
   /usr/bin/python3 ~/DX_Project/pi_robot_server.py
   ```

2. **백엔드·프론트**: 백엔드(:8080)와 프론트(:5173)를 실행한다. 프론트에는 `VITE_API_BASE_URL`이 필요하다.
3. **PC 런타임**: 기기 등록 값을 환경변수로 넣고 실행한다. 토큰은 소스나 문서에 저장하지 않는다.

   ```powershell
   $env:ROBOT_DEVICE_ID = '등록된_ID'
   $env:ROBOT_HTTP_URL = 'http://백엔드IP:8080'
   $env:ROBOT_WS_URL = 'ws://백엔드IP:8080/ws/devices'
   $credential = Get-Credential -UserName robot -Message '암호 칸에 기기 인증 키 입력'
   $env:ROBOT_DEVICE_TOKEN = $credential.GetNetworkCredential().Password
   .\.venv\Scripts\python.exe care_runtime.py --host 172.30.1.10
   ```

시작 직후는 항상 전원 OFF다. 프론트에서 전원을 켜야 주행과 탐지가 시작된다.
PC–Pi 연결이 끊기면 정지한 뒤 자동으로 다시 접속하고, 새 영상으로 대상을 확인한 다음 작업을 이어간다.

## 3. 설정 변경 (`care_config.json`)

### 정지 원인 확인

런타임의 `.runtime/care.log`에는 상태가 바뀔 때 정지 이유·탐지 상태·영상 나이·선명도·남은 삼킴 위험 종류가 기록된다.
`.runtime/care_state.json`은 1초마다 갱신되며 현재 객체와 마커도 확인할 수 있다.
`DETECTION BLUR`는 흐린 영상, `DETECTION STALE`은 오래된 분석 결과,
`CAMERA UNAVAILABLE`은 영상 수신 불가를 뜻한다.
사용자가 선택한 물체는 다른 종류의 삼킴 위험물이 함께 보여도 이송한다.
다른 위험물은 처리 완료로 바꾸지 않으며, 이송 후 남은 위험물이 있으면 정지한다.
같은 종류가 여러 개 보여 대상을 구분할 수 없으면 `MULTIPLE TARGETS OF SAME CLASS`로 정지한다.
전원 ON은 모터 확인 응답을 받으면 접수 완료되지만, 실제 전진은 유효한 새 탐지 결과 이후 시작한다.
탐지 준비가 길어져도 정상적으로 정지 응답을 받았다면 전원 ON 의도를 취소하지 않는다.

재개 API는 현재 활성 프로필의 미처리 삼킴 위험만 검사한다. 현재 카메라에서 감지한 위험에 대한 정지는 유지한다.
실제 처리 화면은 선택한 대상의 완료와 기기 주행 상태를 구분하며 별도의 자동 재개 명령을 보내지 않는다.
남은 위험물은 사진 아래 목록에서 선택한다. 처리 요청 중에는 대상 전환을 막는다.

| 항목 | 기본값 | 의미 |
| --- | --- | --- |
| `removal_absence_seconds` | 2.0 | 제거 완료로 보는 연속 미검출 시간. 2초 미만은 거부한다. |
| `observation_ttl` | 3.0 | 이 시간보다 오래된 탐지 결과는 쓰지 않는다. |
| `max_observation_gap` | 1.0 | 관측이 이만큼 끊기면 미검출 시간을 다시 센다. |
| `marker_id` | 0 | 하역 지점 ArUco ID. |
| `marker_stop_fill` | 0.05595 | 이송 도착으로 보는 마커 크기. 2026-09-23 실물 측정값(이전 0.13126). 미는 물체가 마커를 가리면 fill이 실제보다 작게 읽히므로 더 멀리서 멈추도록 잡았다. |
| `bearing_deadband` | 0.12 | 이 안쪽은 정면으로 보고 전진한다. |
| `coarse_bearing` | 0.35 | 이 밖의 오차는 멈추지 않고 연속 회전한다. 데드밴드보다 크고 1보다 작아야 한다. |
| `turn_pulse_seconds` | 0.12 | 데드밴드와 `coarse_bearing` 사이에서 쓰는 회전 펄스 길이. |
| `settle_seconds` | 0.35 | 펄스 회전 뒤 다음 판단까지 기다리는 시간. 연속 회전 구간에는 적용하지 않는다. |
| `capture_seconds` | 0.8 | 중앙 정렬된 물체를 반원형 팔 안으로 확보하기 위한 전진 시간. |
| `marker_search_timeout_seconds` | 12.0 | 마커 미검출 시 우회전 탐색 제한 시간. 탐색 중 전진하지 않는다. |
| `reverse_seconds` | 1.0 | 이송 후 후진 시간. |
| `drop_verify_seconds` | 1.0 | 후진 후 물체와 마커 근접을 연속 확인하는 시간. |
| `drop_verify_timeout_seconds` | 10.0 | 안전 구역 배치 확인 제한 시간. |
| `drop_verify_radius_ratio` | 0.45 | 화면 너비·높이로 정규화한 물체–마커 중심 거리 허용값. 2026-09-23 실물 배치에서 측정한 중심 거리 약 0.42를 포함한다. |
| `turnaround_seconds` | 1.0 | 이송 후 회전 시간. |
| `action_timeout_seconds` | 120.0 | 처리 요청 제한 시간. 넘기면 실패로 보고한다. |

바꾼 값은 다음 실행부터 적용된다. `--config` 로 다른 파일을 지정할 수도 있다.

## 4. 아직 확정·검증되지 않은 것

- **확보·후진·회전은 시간 제어다.** 각각 0.8초·1초·1초의 임시값이며 측정한 거리나 180도 각도가 아니다.
  실물에서 `capture_seconds`·`reverse_seconds`·`turnaround_seconds`를 조정해야 한다.
- **주사위는 아직 탐지되지 않는다.** 코드 경로(탐지·백엔드 라벨·이송 대상)는 연결했지만 현재 가중치의
  클래스는 `object.pt`가 battery·coin·marble, `hazard.pt`가 socket·wire다. `dice` 클래스를 학습한
  가중치로 교체해야 주사위 이송을 시연할 수 있다.
- 반원형 미는 부품에서 물체가 카메라 아래로 사라지는 시점, 마커 가림, 후진 뒤 물체·마커가 함께 보이는 배치를 실물로 확인해야 한다.
- 2026-09-22에 실제 로봇·백엔드로 직접 제거 후 재개를 확인했다. 자동 재개 제거 등 이번 수정 뒤 실물 재시험은 아직 필요하다.
- Pi 서버 부팅 자동 실행과 PC에서의 서버 시작·정지 명령은 요청에 따라 적용하지 않았다.

## 5. 프론트 표시

- 기기 카드에 `전원 ON` 또는 `전원 OFF · 통신 유지`를 표시한다. 기기 보고가 오래되면
  `전원 상태 확인 전`으로 두고 전원 상태를 단정하지 않는다.
- 작업 단계(`자동 주행 중`, `위험물 앞에서 정지`, `제거 여부 재확인 중`, `위험물 이송 중` 등)를 함께 보여준다.
- 제거 재확인과 안전 이송은 접수 후 결과를 3초마다 조회한다. 서버가 완료를 알려줄 때만 완료로 표시한다.
  실제 모드에서는 별도의 `청소 재개` 명령을 중복 전송하지 않고, 남은 위험 여부와 최신 주행 상태를 따로 보여준다.

## 6. 프론트 없이 확인하는 방법

프론트를 띄우지 않고도 전원·탐지·재확인·이송 경로를 확인할 수 있다. 세 가지 중에 고른다.

### A. 아무것도 없이 자동 검사 (프론트·백엔드·로봇 불필요)

```powershell
.\.venv\Scripts\python.exe check_without_frontend.py
```

`mock_backend.py`와 실제 WebSocket 모듈, 실제 작업 상태기를 띄우고 모터·카메라만 대역으로 바꿔
전원 켜기 → 위험물 정지 → 재확인 → 이송 → 전원 끄기를 순서대로 실행한다. 15개 항목을
`PASS`/`FAIL`로 출력하고, 실패가 있으면 종료 코드 1이다. 로봇이 움직이는지는 확인하지 않는다.
확인하는 것은 어떤 명령이 접수되는지, 기기가 무엇을 보고하는지, 백엔드가 거부할 응답을 거부하는지다.

### B. 실제 로봇 + 목 백엔드 (프론트·Spring·DB 불필요)

실제 Pi와 모터로 움직임까지 보되 백엔드와 프론트는 띄우지 않는 방법이다. 터미널 세 개를 쓴다.

1. 목 백엔드:

   ```powershell
   .\.venv\Scripts\python.exe mock_backend.py
   ```

2. PC 런타임 (목 백엔드 기본값에 맞춘 환경변수):

   ```powershell
   $env:ROBOT_DEVICE_ID = 'robot-test'
   $env:ROBOT_DEVICE_TOKEN = 'local-test-token-0123456789abcdef'
   $env:ROBOT_HTTP_URL = 'http://127.0.0.1:18080'
   $env:ROBOT_WS_URL = 'ws://127.0.0.1:18081/ws/devices'
   .\.venv\Scripts\python.exe care_runtime.py --host 172.30.1.10
   ```

3. 프론트 버튼 대신 명령을 직접 보낸다.

   ```powershell
   $mock = 'http://127.0.0.1:18080/mock/command'
   function Send-Robot($body) {
     Invoke-RestMethod -Method Post -Uri $mock -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 4)))
   }

   Send-Robot @{ command = 'POWER_ON' }
   Send-Robot @{ command = 'POWER_OFF' }
   Send-Robot @{ command = 'PAUSE' }

   $hazard = [guid]::NewGuid().ToString()
   Send-Robot @{ command = 'RECHECK_HAZARD'; parameters = @{ hazardId = $hazard; objectLabel = '동전' } }
   Send-Robot @{ command = 'RELOCATE';       parameters = @{ hazardId = $hazard; objectLabel = '동전' } }
   ```

   한글 라벨은 UTF-8 바이트로 보내야 한다. 위 함수가 그 처리를 한다.
   `hazardId`는 목 백엔드에서는 아무 UUID나 쓸 수 있다. 위험물 명령은 정지 상태에서만 접수되므로
   삼킴 위험물이 보여 스스로 멈춘 뒤에 보내거나, 먼저 `PAUSE`를 보낸다.

목 백엔드 터미널에 `WS COMMAND SENT` → `WS COMMAND_ACK` → `WS COMMAND_RESULT`가 차례로 찍힌다.
상태 보고는 `WS RECEIVED ... power=True task=RUNNING` 형태로 1초 간격으로 보인다. 여기서
`power`와 `task`가 프론트 전원 표시와 작업 단계 표시에 쓰이는 값이다.
결과가 계약에 맞지 않으면 목 백엔드가 `INVALID_MESSAGE`로 거부한다. 실제 Spring과 같은 기준이다.

### C. 실제 백엔드 + HTTP 호출 (프론트만 제외)

백엔드 접수 규칙과 DB 저장까지 보려면 REST를 직접 호출한다. 등록된 `deviceId`와 실제 `hazardId`가 필요하고,
`Idempotency-Key`는 요청마다 새 UUID를 쓴다.

| 목적 | 호출 |
| --- | --- |
| 전원 켜기·끄기 | `POST /api/v1/devices/{deviceId}/commands/power-on` · `.../power-off` |
| 청소 정지·재개 | `POST /api/v1/devices/{deviceId}/commands/pause` · `.../resume` |
| 명령 결과 확인 | `GET /api/v1/devices/{deviceId}/commands/{commandId}` |
| 직접 제거 재확인 | `POST /api/v1/hazards/{hazardId}/removal-checks` |
| 안전 이송 | `POST /api/v1/hazards/{hazardId}/relocations` |
| 처리 결과 확인 | `GET /api/v1/safety-actions/{actionId}` |
| 전원·작업 상태 확인 | `GET /api/v1/devices/{deviceId}/robot-state` |

```powershell
$api = 'http://localhost:8080/api/v1'
$device = '등록된_ID'
$receipt = Invoke-RestMethod -Method Post -Uri "$api/devices/$device/commands/power-on" `
  -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() } -ContentType 'application/json' -Body '{}'
Invoke-RestMethod -Uri "$api/devices/$device/commands/$($receipt.commandId)"
Invoke-RestMethod -Uri "$api/devices/$device/robot-state"
```

본문은 전원·정지 명령과 이송 모두 `{}`다. 이송에 `safeZoneId`를 넣으면 거부된다. 목적지는 ArUco ID 0이다.
위험물 명령은 기기가 정지 상태로 보고된 뒤에만 접수된다. PC 런타임이 실행 중이 아니면
`DEVICE_NOT_CONTROLLABLE`로 거부되므로, 전원 명령 전에 런타임이 붙어 있어야 한다.

## 7. 검증

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m unittest discover -s . -p test_bridge.py -v
.\.venv\Scripts\python.exe check_without_frontend.py
```

백엔드:

```powershell
cd ../backend
.\gradlew test --offline
```

프론트:

```powershell
cd ../frontend
npx tsc -b
npm run lint
node --test "tests/*.test.mjs"
```

모두 실제 로봇을 움직이지 않는 로컬 검사다. 프론트 전원·처리 화면은 타입 검사까지만 확인했고
브라우저 동작은 직접 눌러 확인해야 한다.

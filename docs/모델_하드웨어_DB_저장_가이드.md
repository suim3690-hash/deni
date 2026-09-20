# 다른 PC에서 모델·하드웨어 데이터 저장하기

> 2026-09-20: 신규 연동은 [Socket 상태 수신·HTTP 이미지 업로드](Socket_명세서_백엔드-하드웨어.md)를 사용한다.
> 아래 직접 DB 저장 방식은 v1 개발용이다. 같은 기기의 Socket 상태 입력과 병행하지 않는다.
> 직접 DB 입력은 기존과 같이 원본만 저장하지만, 새 HTTP 업로드는 지원 라벨을 hazards에도 연결한다.

기존 공유 PostgreSQL에 접속한다. 다른 PC에 PostgreSQL 서버를 새로 설치할 필요는 없다.
Python 드라이버 또는 pgAdmin/DBeaver 같은 클라이언트만 있으면 된다.

## 1. 접속과 최초 준비

| 항목 | 값 |
| --- | --- |
| Host | `project-db-campus.smhrd.com` (http:// 없이 입력) |
| Port | `3310` |
| Database | `campus_lgdx_2` |
| 기존 개발 계정 | `campus_lgdx_2` |
| Password | 별도로 전달받은 비밀번호. 소스·Git·프론트 코드에 넣지 않음 |
| Schema | `public` |

PowerShell에서 네트워크 확인:

```powershell
Test-NetConnection project-db-campus.smhrd.com -Port 3310
```

접속 실패 시 서버 제공자의 외부 접속 허용·접속 PC의 IP 제한·방화벽을 확인한다.
Python 저장은 백엔드 프로세스 없이도 가능하다. 프론트에서 보려면 백엔드와 프론트를 실행해야 한다.
DB 관리자에게 두 테이블만 쓰는 별도 계정을 요청하면 기존 아이·위험 테이블의 오작동을 줄일 수 있다.
이번 구현에서는 새 DB 계정·비밀번호를 생성하거나 기존 계정 권한을 변경하지 않았다.

먼저 실제 아이를 등록하고 기존 `POST /api/v1/devices`로 기기를 연결한다.
`deviceId`는 두 PC가 동일한 값을 사용한다. 임의의 테스트 기기를 기존 아이에게 자동 연결하지 않는다.

```powershell
$body = @{ childId = '실제 아이 UUID'; deviceId = 'robot-001'; name = '거실 로봇' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/devices' -ContentType 'application/json' -Body $body
```

위 요청을 다른 PC에서 보내면 localhost 대신 **백엔드 실행 PC의 주소**를 사용한다.
기기 목록은 DB에서 `SELECT id, child_id, name FROM devices;`로 확인할 수 있다.
현재는 아이 한 명당 기기 한 대 정책이다.

## 2. 탐지 시 저장: detection_events

| 컬럼 | 타입 | 작성 방법 |
| --- | --- | --- |
| `event_id` | UUID, PK | 최초 탐지 때 UUID v4 생성. 재전송은 같은 ID 사용. DB PK로 중복 행 차단 |
| `device_id` | VARCHAR(100), FK | 등록된 `devices.id` |
| `model_type` | VARCHAR(10) | `HAZARD` 또는 `OBJECT` |
| `object_label` | VARCHAR(100) | 물체 이름/라벨. 공백만 입력 불가 |
| `detected_at` | TIMESTAMPTZ | DB가 최초 INSERT 수신 시 서버 시각을 자동 기록 |
| `frame_image` | BYTEA | 바운딩 박스를 그린 JPEG/PNG 파일의 실제 바이트, 1바이트~5MiB |
| `image_content_type` | VARCHAR(20) | `image/jpeg` 또는 `image/png` |

`detected_at`은 네트워크 전송 후 DB에 도착한 시각이다. 카메라 촬영 시각과 정확히 같지는 않다.
실제 촬영 시각이 별도로 필요하면 후속으로 `captured_at`을 추가해야 한다.
이미지는 파일 경로나 base64 문자열 대신 바이트를 저장하므로 다른 PC에서도 읽을 수 있다.
바운딩 박스 처리는 탐지 PC가 먼저 수행한다. 백엔드가 이미지를 그리거나 모델을 실행하지 않는다.

한 행은 **물체 하나의 탐지 결과**다. 같은 프레임의 물체가 여럿이면 물체별 이벤트 ID를 사용한다.
같은 이벤트 재전송에서 이미지·라벨·모델·기기를 바꾸지 않는다. UUID 충돌은 DB가 중복 저장을 거부하고,
제공된 Python 함수는 기존 내용까지 비교한다. 새 프레임의 반복 탐지를 하나로 묶는 기능은 없다.
현재 프레임 이미지는 행마다 저장하므로 매 프레임 저장하면 DB가 빠르게 커진다.
연속 영상 전체가 아니라 저장할 탐지 이벤트를 선정해 전송하고, 보관 기간은 팀에서 정한다.

## 3. 최신 상태 저장: robot_live_state

| 컬럼 | 타입 | 작성 방법 |
| --- | --- | --- |
| `device_id` | VARCHAR(100), PK/FK | 기기당 최신 한 행만 유지 |
| `operation_state` | VARCHAR(20) | `RUNNING` 진행 중, `PAUSED` 일시정지, `RELOCATING` 장애물 이송 중, `UNKNOWN` 미확인 |
| `movement_state` | VARCHAR(20) | `FORWARD` 직진, `TURNING` 회전, `BACKWARD` 후진, `STOPPED` 정지, `UNKNOWN` 미확인 |
| `movement_duration_ms` | BIGINT, nullable | 현재 이동 구간 시작부터 경과 시간(ms). 미측정은 NULL |
| `movement_distance_m` | NUMERIC(12,3), nullable | 현재 이동 구간 누적 이동 거리(m). 미측정은 NULL, 후진도 양수 |
| `sampled_at` | TIMESTAMPTZ | 탐지 PC가 실제 상태를 측정한 시간대 포함 시각 |
| `received_at` | TIMESTAMPTZ | DB가 새로운 상태를 수신한 시각. 자동 기록 |

동작과 이동 상태는 독립적이다. 예: 장애물 이송 중(`RELOCATING`) 후진(`BACKWARD`).
현재 구간은 방향/이동 동작이 바뀔 때 새로 시작한다. 시간으로 거리를 추정하지 말고 측정값이 없으면 NULL을 쓴다.
정지 이후에도 과거 모든 이동 기록이 필요한 경우 별도 이동 이력 테이블이 필요하며 현재 테이블은 최신 상태 전용이다.

기기당 하나의 상태 작성 프로그램을 사용하고, **변화 시 + 1초 간격**으로 최신 상태를 보낸다(초기 운영 기준).
PC 시계를 동기화한다. 미래 `sampled_at`은 거부한다. 과거 보고는 무시하고,
동일 시각·내용 재전송은 수신 시각도 갱신하지 않는다. 동일 시각의 다른 내용은 오류다.
DB 트리거가 이 규칙을 적용하므로 직접 SQL로 갱신해도 과거 패킷이 최신 상태를 덮어쓰지 못한다.

## 4. 다른 PC에서 Python 사용

저장소를 pull하거나 `hardware/examples/robot_db_writer.py`를 전달받아 모델 프로그램 옆에 둔다.

```powershell
py -m pip install "psycopg[binary]"
$env:DB_HOST = 'project-db-campus.smhrd.com'
$env:DB_PORT = '3310'
$env:DB_NAME = 'campus_lgdx_2'
$env:DB_USER = 'campus_lgdx_2'
$taskDbCredential = Get-Credential -UserName $env:DB_USER -Message '제공받은 DB 비밀번호를 입력하세요'
$env:DB_PASSWORD = $taskDbCredential.GetNetworkCredential().Password
```

기존 모델 코드에 추가할 예제:

```python
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4
from robot_db_writer import connect, save_detection, save_live_state

# 모델이 바운딩 박스를 그린 JPEG/PNG 결과를 만든 다음 읽는다.
# 재시도할 때는 이 ID와 이미지 바이트를 그대로 유지한다.
event_id = uuid4()
frame = Path("annotated_frame.jpg").read_bytes()
sampled_at = datetime.now(timezone.utc)

with connect() as conn:
    save_detection(conn, event_id=event_id, device_id="robot-001",
                   model_type="HAZARD", label="레고", image_bytes=frame)
    save_live_state(conn, device_id="robot-001", operation_state="RUNNING",
                    movement_state="FORWARD", sampled_at=sampled_at,
                    movement_duration_ms=1200, movement_distance_m=0.35)
```

위 값은 사용 예시다. 실제 프로그램에서는 센서·제어기 값을 전달한다.
프레임 파일 대신 OpenCV `imencode`로 생성한 JPEG/PNG 바이트도 전달할 수 있다.
함수는 성공 시 커밋, 오류 시 롤백한다. 연결 오류 후 재접속·재시도는 호출 프로그램에서 처리한다.
프로그램 재시작 후에도 같은 탐지를 재전송할 수 있다면 이벤트 ID·원본 바이트를 로컬에 보존해야 한다.
호출 때마다 UUID를 새로 만들면 재전송도 별개 이벤트로 저장된다.

SQL을 직접 사용하는 다른 언어에서도 파라미터 바인딩으로 아래 UPSERT를 실행한 뒤 커밋한다:

```sql
INSERT INTO robot_live_state
    (device_id, operation_state, movement_state, sampled_at, movement_duration_ms, movement_distance_m)
VALUES (:device_id, :operation_state, :movement_state, :sampled_at, :duration_ms, :distance_m)
ON CONFLICT (device_id) DO UPDATE SET
    operation_state = EXCLUDED.operation_state,
    movement_state = EXCLUDED.movement_state,
    sampled_at = EXCLUDED.sampled_at,
    movement_duration_ms = EXCLUDED.movement_duration_ms,
    movement_distance_m = EXCLUDED.movement_distance_m;
```

`:변수` 표기는 설명용이며 드라이버에 맞는 파라미터 문법을 사용한다.
`devices`, `hazards`, `operation_requests`, Flyway 이력은 외부 작성 프로그램에서 직접 수정하지 않는다.

## 5. 프론트 확인과 기존 API 관계

| 경로 | 응답 |
| --- | --- |
| `GET /api/v1/devices/{deviceId}/robot-state` | 현재 동작·이동·시간·거리·수신 시각·stale |
| `GET /api/v1/devices/{deviceId}/detections` | 최근 50개 원본 탐지 목록. 이미지 바이트는 목록에 포함하지 않음 |
| `GET /api/v1/devices/{deviceId}/detections/{eventId}/image` | DB에 저장한 JPEG/PNG 이미지 |

홈에서 등록 기기가 있으면 기존 대시보드 갱신과 함께 로봇 상태를 **5초 간격**으로 조회한다.
이는 실시간에 가까운 폴링이며 즉시 푸시 방식이 아니다. 측정·수신 시각 중 하나라도 10초 이상 오래되면
현재 상태는 UNKNOWN, 시간·거리는 null로 표시한다. 새 백엔드 코드 적용 후 재시작해야 새 경로가 동작한다.
탐지 목록/이미지 조회 API는 제공하지만 별도 원본 탐지 목록 UI는 이번 범위에 추가하지 않았다.

기존 기기 상태 카드의 연결·배터리 정보(`devices`)와 이번 로봇 동작 보고(`robot_live_state`)는 구분한다.
이번 보고만으로 기존 카드의 온라인 상태·제어 가능 여부가 바뀌지는 않는다.
`detection_events`는 모델 원본이므로 위험도·아이 프로필을 판단하는 기존 `hazards` 및 월간 위험 집계에
자동 포함되지 않는다. HAZARD 모델 결과라는 이유만으로 위험도나 안전 완료를 추정하지 않는다.
후속 연동에서 원본을 검증·분류해 위험 기록으로 연결하는 규칙을 정해야 한다.

## 6. 읽기 전용 확인 SQL

```sql
SELECT event_id, device_id, model_type, object_label, detected_at,
       image_content_type, octet_length(frame_image) AS image_bytes
FROM detection_events ORDER BY detected_at DESC LIMIT 20;

SELECT * FROM robot_live_state ORDER BY received_at DESC;
```

이미지는 SQL 결과의 BYTEA 글자 표시 대신 이미지 GET 경로로 확인한다.

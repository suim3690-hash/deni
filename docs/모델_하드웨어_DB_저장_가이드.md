# 모델·하드웨어 데이터 저장 가이드

현재 경로는 **로봇 PC → 백엔드 HTTP/WebSocket → 공유 PostgreSQL**이다. 하드웨어 PC는 DB에 직접 접속하거나 `hazards`·`robot_live_state`를 직접 수정하지 않는다. 이전 `hardware/examples/robot_db_writer.py`는 레거시 예제이며 통합 런타임과 병행하지 않는다.

| 입력 | 전달 경로 | DB 테이블 | 시간 기준 |
| --- | --- | --- | --- |
| 탐지 이벤트 ID·모델·라벨·바운딩 박스 JPEG/PNG | `POST /api/v1/hardware/detections` | `detection_events` | DB 최초 수신 시각 `detected_at` |
| 지원 라벨의 위험도·상태·사진 URL | 백엔드가 아이 성장단계로 계산 | `hazards` | 탐지 시각, 위험 처리 시각 |
| 전원·작업·동작·이동 정보 | WebSocket `ROBOT_STATE` | `robot_live_state` | 기기 측정·서버 수신 시각 |
| 제어 ACK·완료·실패 | WebSocket `COMMAND_ACK`·`COMMAND_RESULT` | `operation_requests`, `device_command_delivery` | 실제 명령 결과 시각 |

## 탐지와 위험의 관계

| 항목 | 현재 규칙 |
| --- | --- |
| 원본 이벤트 | 물체별 UUID 한 건. 같은 프레임의 여러 물체는 각기 ID가 있지만 같은 바운딩 박스 사진을 가질 수 있음 |
| 재전송 | 같은 `eventId`·기기·모델·라벨·이미지만 허용. 내용이 다르면 충돌 |
| 지원 라벨 | 동전·구슬·배터리·주사위 → `SWALLOW`; 전선·콘센트 → `LIVING` |
| 미지원 라벨·지원 연령 밖 | 원본만 저장, `hazardId=null` |
| 위험 병합 | 같은 기기·활성 아이·분류·물체 이름의 `ACTIVE` 위험은 한 건. 새 감지가 더 최신이면 시간·사진·위험도 갱신 |
| 위험 해결 | 삼킴 위험은 기기 재확인/이송 성공 증거 후 `RESOLVED`. 생활공간 위험은 사용자 확인 완료를 근거로 `RESOLVED` |

| 성장단계 | SWALLOW | LIVING |
| --- | --- | --- |
| 영아기 | HIGH | HIGH |
| 걸음마 | VERY_HIGH | HIGH |
| 유아 활동기 | MEDIUM | VERY_HIGH |

V13 마이그레이션은 기존 `ACTIVE`·`RESOLVED` 삼킴 위험 모두를 현재 아이 단계로 다시 계산한다. 위험도는 모델 내부 숫자 점수가 아니라 백엔드 정책이다. 원본 이미지는 `BYTEA`로 저장하며 최대 5MiB의 JPEG/PNG만 수신한다. 사진·로컬 전송 대기열의 보관 기간은 아직 정하지 않았다.
V13 적용 이후 성장단계만 바뀌어도 과거 위험 행을 즉시 재계산하는 기능은 아직 없으므로, 장기 운영 전 갱신 정책을 정해야 한다.

## 최신 로봇 상태

`robot_live_state`는 **기기당 최신 행 하나**다. `operation_state`(RUNNING/PAUSED/RELOCATING/UNKNOWN), `movement_state`(FORWARD/TURNING/BACKWARD/STOPPED/UNKNOWN), 전원·작업 상태, 선택적 이동 시간(ms)·거리(m)를 보관한다. 측정하지 못한 값은 `null`로 둔다. 과거 패킷은 최신 상태를 덮어쓰지 않으며, 오래된 보고는 API에서 `stale=true`·미확인 상태로 다룬다. 프론트는 약 5초마다 조회한다.

## 읽기 전용 확인

공유 PostgreSQL의 호스트는 `project-db-campus.smhrd.com`, 포트 `3310`, DB명은 `campus_lgdx_2`다. 비밀번호는 별도 전달받고 Git에 넣지 않는다. pgAdmin/DBeaver에서 다음을 조회할 수 있다.

```sql
SELECT event_id, device_id, model_type, object_label, detected_at,
       octet_length(frame_image) AS image_bytes, hazard_id
FROM detection_events ORDER BY detected_at DESC LIMIT 20;

SELECT id, child_id, object_type, object_name, risk_level,
       status, acknowledged_at, detected_at, capture_image_url
FROM hazards ORDER BY detected_at DESC LIMIT 20;

SELECT * FROM robot_live_state ORDER BY received_at DESC;
SELECT installed_rank, version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

실제 사진은 `GET /api/v1/devices/{deviceId}/detections/{eventId}/image`로 확인한다. 기기 입력 계약은 [Socket 명세](Socket_명세서_백엔드-하드웨어.md), DB 변경 이력은 [백엔드 README](../backend/README.md)를 따른다.

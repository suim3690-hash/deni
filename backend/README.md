# 백엔드

Java 21 · Spring Boot · Gradle · PostgreSQL · Flyway. 프론트에는 HTTP API를 제공하고, 로봇 PC와는 WebSocket(명령·상태) 및 HTTP(탐지 이미지)로 통신한다. DB에는 백엔드만 접속한다.

## 실행·검증

```powershell
cd backend
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# .env에 DB_PASSWORD, ROBOT_DEVICE_ID, ROBOT_DEVICE_TOKEN 설정
powershell -ExecutionPolicy Bypass -File .\run-local.ps1
```

`DB_PASSWORD`는 별도 전달받는다. 기기 토큰은 로봇 PC와 같아야 하며 Git에 올리지 않는다. 로컬 프론트 외의 Origin을 허용하려면 `APP_CORS_ALLOWED_ORIGINS`를 설정한다. **공용 DB와 한 기기에는 백엔드 한 인스턴스만 실행**한다.

```powershell
.\gradlew.bat test                 # DB 통합 테스트 제외
$env:RUN_DB_TESTS = 'true'
.\gradlew.bat test                 # 공유 DB를 사용하는 통합 테스트 포함
```

## HTTP API

기본 경로는 `/api/v1`이다. 상세 요청·응답은 [API 명세](../docs/API_명세서_프론트-백엔드.md)를 따른다.

| 영역 | 주요 경로 | 현재 동작 |
| --- | --- | --- |
| 아이·프로필 | `POST /children`, `PATCH /children/{id}`, `GET /children/{id}/safety-profile` | 등록·월령/단계 계산·변경 이력 |
| 홈·리포트 | `GET /dashboard`, `GET /reports/monthly` | 선택 아이의 위험·로봇 상태·월간 집계 |
| 기기 | `POST /devices`, `POST /devices/{id}/active-child`, `GET /devices/{id}/status`, `GET /devices/{id}/robot-state` | 데모 프로필 3명이 한 기기를 공유, 마지막 선택 프로필에 새 탐지 귀속 |
| 위험 | `GET /hazards`, `GET /hazards/{id}`, `POST /hazards/{id}/acknowledgements` | 목록·상세·생활공간 위험 확인 완료 및 `RESOLVED` 처리 |
| 탐지 | `POST /hardware/detections`, `GET /devices/{id}/detections`, `GET /devices/{id}/detections/{eventId}/image` | 원본 이벤트·이미지 저장, 지원 라벨은 위험 건 생성/갱신 |
| 제어 | `POST /devices/{id}/commands/{pause\|resume\|power-on\|power-off}`, `GET /devices/{id}/commands/{commandId}` | 기기에 명령 전달, 실제 결과 조회 |
| 안전 처리 | `POST /hazards/{id}/removal-checks`, `POST /hazards/{id}/relocations`, `GET /safety-actions/{id}` | 기기 재확인/이송 성공 증거가 있을 때만 위험 해결 |

명령·아이 등록은 `Idempotency-Key`를 사용하고 탐지 업로드는 `eventId`로 중복을 막는다. 명령 접수만으로 성공 처리하지 않는다. 미처리 삼킴 위험이 있으면 일반 재개를 거부한다. 생활공간 위험은 확인 완료 시 `RESOLVED`로 전환되어 활성 지도에서 제외된다. 같은 라벨이 계속 보이는 동안은 완료 상태를 유지하고, 30초 이상 미검출 뒤 다시 보이면 새 위험으로 처리한다.

| 아이 단계 | 삼킴 위험 | 생활공간 위험 |
| --- | --- | --- |
| 영아기 (0~11개월) | HIGH | HIGH |
| 걸음마 (12~35개월) | VERY_HIGH | HIGH |
| 유아 활동기 (36~95개월) | MEDIUM | VERY_HIGH |

Flyway V13은 기존 `ACTIVE`·`RESOLVED` 삼킴 위험의 등급을 현재 아이 단계로 다시 계산한다. 과거 기록의 위험도도 바뀌는 데모 정책이며, **백엔드를 다음에 실행할 때** 적용된다.
이후 아이 생년월일 수정이나 자동 성장단계 전환만으로 기존 모든 위험 행을 다시 계산하지는 않는다. 새 감지는 새 기준을 쓰지만 과거 DB 행의 재계산 정책은 후속으로 정해야 한다.

## DB

| 테이블 | 저장 정보 |
| --- | --- |
| `children`, `profile_history` | 아이·성장단계와 변경 이력 |
| `devices`, `device_children` | 기기·연결 가능한 아이·현재 활성 아이 |
| `detection_events` | 원본 모델·라벨·수신 시각·바운딩 박스 이미지 |
| `hazards` | 분류된 위험·위험도·해결 상태·선택 사진 URL·생활 위험 확인 완료 시각 |
| `robot_live_state` | 기기별 최신 전원·작업·이동 상태 |
| `operation_requests`, `device_command_delivery` | 요청 중복 키·전달/완료 결과 |

Flyway V1~V13을 사용한다. 한 프레임의 물체는 각각 원본 이벤트로 저장하지만, 같은 기기·아이·분류·이름의 `ACTIVE` 위험은 한 건으로 합친다. 새 감지가 더 최신이면 그 위험의 시간·사진·위험도를 갱신한다. 지원하지 않는 라벨은 원본에만 저장한다.

보호자 인증·접근 권한, TLS, 실측 지도 좌표, 생활공간 위험 해제, 자동 이송은 아직 없다. 실제 장치 실행 및 제한은 [한 PC 통합 실행](../docs/한_PC_통합_실행_가이드.md)과 [통합 테스트](../docs/통합_테스트_시나리오.md)를 참고한다.

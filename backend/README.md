# Deuni 백엔드

Java 21 · Spring Boot 4.1.1 · PostgreSQL · Flyway V1~V8 · Gradle.
프론트 HTTP API, DB 저장, 하드웨어 WebSocket 중계를 담당한다.

## 구현 API

기본 경로 `/api/v1`.

| 기능 | API | 상태 |
| --- | --- | --- |
| 아이 등록·수정 | POST `/children`, PATCH `/children/{id}` | DB 저장·월령 계산·프로필 갱신 |
| Safety Profile | GET `/children/{id}/safety-profile` | 0~11 / 12~35 / 36~95개월 |
| 홈 | GET `/dashboard?childId=...` | 아이·기기·활성 위험·리포트 요약 |
| 위험 목록·상세 | GET `/hazards?deviceId=...&status=ACTIVE`, `/hazards/{id}` | DB 조회 |
| 월간 리포트 | GET `/reports/monthly?childId=...&month=YYYY-MM` | 월별 집계·프로필 변경 이력 |
| 기기 등록·상태 | POST `/devices`, GET `/devices/{id}/status` | 아이당 한 기기 |
| 최신 로봇 상태 | GET `/devices/{id}/robot-state` | 10초 경과 시 UNKNOWN |
| 탐지 원본·이미지 | GET `/devices/{id}/detections`, `/devices/{id}/detections/{eventId}/image` | 최근 50건·JPEG/PNG |
| 모델 입력 | POST `/hardware/detections` | 기기 인증·multipart 이미지·원본/위험 저장 |
| 일시정지 | POST `/devices/{id}/commands/pause` | Socket 연결·최신 온라인 보고·확인된 이동 상태가 있을 때 전달 대기열에 저장 |
| 명령 결과 | GET `/devices/{id}/commands/{commandId}` | 전달·실제 결과 조회 |
| 직접 제거 재확인 | POST `/hazards/{id}/removal-checks`, GET `/safety-actions/{id}` | 접수 기록만, 모델 재검사 미연결 |
| 재개·이송 | POST `/devices/{id}/commands/resume`, `/hazards/{id}/relocations` | 안전 계약 미확정으로 409 |

등록·명령 요청은 기존 Idempotency-Key 계약을 따른다. 위험 업로드는 eventId로 중복을 검사한다.
프론트 UI는 받은 원본을 유지했다. 최신 홈의 전원 버튼은 ThinQ 안내/조회이며 일시정지 실행 버튼이 아니다.
명령 HTTP 호출 경로는 준비됐고 프론트 담당자의 버튼 연결이 필요하다. ThinQ 실제 제어는 구현하지 않았다.

다른 PC의 프론트에서 접속할 때는 백엔드 실행 전에 해당 Origin을 쉼표로 구분해 설정한다. 주소 끝에 `/`를 붙이지 않는다.

```powershell
$env:APP_CORS_ALLOWED_ORIGINS = 'http://localhost:5173,http://프론트PC_IP:5173'
```

## DB

| 테이블 | 용도 | 마이그레이션 |
| --- | --- | --- |
| children | 아이·현재 성장단계 | V1, V4 |
| hazards | 분류된 위험·이미지 URL·원본 eventId | V2, V4 |
| profile_history | 성장단계 변경·기준 스냅샷 | V3 |
| devices | 아이 연결·동작·배터리·최근 보고 | V5 |
| operation_requests | 사용자 요청과 중복 키 | V6 |
| detection_events | 원본 탐지·JPEG/PNG 바이트 | V7 |
| robot_live_state | 기기당 최신 상태·이동 정보 | V7 |
| device_command_delivery | PAUSE 전달 상태·만료·결과·완료 시각 | V8 |

V8은 기존 요청을 자동 실행하지 않는다. 새 Socket 연결 중 접수한 PAUSE만 전달 대상으로 저장한다.
접수와 전달 행은 한 트랜잭션에서 저장하며 0.5초 주기로 전송한다. 10초 경과 시 미전송은 EXPIRED,
전송 후 결과 미확인은 UNKNOWN이다. 자동 재전송하지 않으며 늦은 실제 결과로 UNKNOWN을 확정할 수 있다.
실제 완료 결과의 commandId와 deviceId가 일치해야 한다. 일반 PAUSED 상태만으로 명령 완료를 추정하지 않는다.

## 하드웨어 연결

[Socket 명세](../docs/Socket_명세서_백엔드-하드웨어.md)에 헤더·메시지·업로드 형식과 Python 예제가 있다.
현재 한 기기·한 서버 프로세스 기준이다. 백엔드와 하드웨어에 같은 ID·키를 별도로 설정한다.

```powershell
cd backend
$env:ROBOT_DEVICE_ID = 'robot-001'
# ROBOT_DEVICE_TOKEN: 별도 공유한 32자 이상 키를 환경변수 또는 로컬 .env에 설정
$env:DEVICE_STATUS_MAX_AGE_SECONDS = '10'
$env:SERVER_ADDRESS = '0.0.0.0'
powershell -ExecutionPolicy Bypass -File .\run-local.ps1
```

backend/.env의 DB_PASSWORD가 필요하다. 새 PostgreSQL 서버 설치 없이 기존 공유 DB를 사용한다.
키를 설정하지 않으면 하드웨어 접속은 거부된다. 보호자 인증·소유권 검증과 TLS는 아직 없으므로 개발망에서 사용한다.
하드웨어 상태는 Socket으로 한 프로그램만 작성하며 동일 기기의 직접 DB 저장과 병행하지 않는다.

## 검증 및 제한

```powershell
cd backend
# DB_PASSWORD 환경변수에 로컬 비밀번호 설정 후 실행
$env:RUN_DB_TESTS = 'true'
.\gradlew.bat test
```

테스트는 실제 DB를 사용한다. 대부분 롤백하고 실제 Socket 테스트는 고유 테스트 아이와 전용 기기의 행만 정리한다.
기존 위험 원본은 자동 변환하지 않는다. HTTP로 새로 받은 한글 라벨 5종만 현재 프론트 규칙으로 위험에 연결한다.
자동 정지·재개·이송·재검사·보호자 인증·평가 저장은 후속 구현이다. 실제 모터 동작은 검증 전이다.

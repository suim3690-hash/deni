# Safety Care 백엔드

아이 정보와 성장단계별 Safety Profile, 위험 탐지 결과의 저장·조회 및 월간 집계를 제공한다.
실제 구현된 기능을 기준으로 정리한 문서이며, 상세 계약은
[프론트–백엔드 API 명세서](../docs/API_명세서_프론트-백엔드.md)를 참고한다.

> 현재 개발 단계에는 인증·접근 권한 검증이 없다. 하드웨어·탐지 모델도 연결되지 않았으므로
> 외부에 공개하거나 실제 안전 제어 서비스로 사용해서는 안 된다.

## 1. 기술 및 파일 구조

| 구분 | 구성 |
| --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 4.1.1 |
| API | Spring MVC, JSON, 기본 경로 `/api/v1` |
| DB 접근 | Spring Data JPA, PostgreSQL |
| 스키마 관리 | Flyway V1~V6, JPA `ddl-auto=validate` |
| 실행·검증 | Gradle Wrapper, JUnit·Mockito |

```text
backend/
├── README.md
├── .env.example                 # 비밀번호 설정 예시
├── run-local.ps1                # 로컬 .env 로딩 및 서버 실행
└── src/
    ├── main/
    │   ├── java/com/deni/backend/
    │   │   ├── child/           # 아이·프로필·대시보드·리포트 API, 프로필 갱신 스케줄러
    │   │   ├── hazard/          # 위험 저장 서비스·조회 API
    │   │   ├── device/          # 기기 등록·아이 연결·상태 저장/조회
    │   │   ├── operation/       # 일시정지·재확인 요청 접수 기록/조회·미지원 동작 차단
    │   │   └── common/          # 공통 예외·오류 응답·중복 저장 잠금
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/    # V1~V6: children, hazards, profile_history, 중복 방지, devices, operation_requests
    └── test/java/com/deni/backend/
        ├── child/
        ├── hazard/
        ├── device/
        ├── operation/
        └── common/
```

## 2. 구현된 API

아래 경로 앞에는 `/api/v1`을 붙인다. 현재 아이·위험 ID는 UUID 문자열이며 클라이언트는
ID를 해석하지 않는다. 생년월일은 `YYYY-MM-DD`, 시각은 시간대를 포함한 ISO 8601을 사용한다.

| 기능 | Method | 경로 | 입력 | 응답·상태 | DB 처리 |
| --- | --- | --- | --- | --- | --- |
| 아이 등록 | POST | `/children` | 이름·생년월일, `Idempotency-Key` UUID 헤더 | `201`, 아이·계산된 프로필 | `children` INSERT |
| 아이 수정 | PATCH | `/children/{childId}` | 변경할 이름 또는 생년월일 | `200`, 수정된 아이·프로필 | `children` UPDATE |
| 프로필 상세 | GET | `/children/{childId}/safety-profile` | 아이 UUID | `200`, 월령·단계·단계명·점검 기준·적용 시각 | 조회, 단계 변경 시 UPDATE |
| 대시보드 | GET | `/dashboard?childId={childId}` | 아이 UUID query 필수 | `200`, 아이·현재 프로필·활성 위험 | 조회, 단계 변경 시 UPDATE |
| 위험 목록 | GET | `/hazards?deviceId={deviceId}&status=ACTIVE` | 기기 ID·상태 query 필수 | `200`, `{ "items": [...] }`, 감지 시각 내림차순 | `hazards` SELECT |
| 위험 상세 | GET | `/hazards/{hazardId}` | 위험 UUID | `200`, 물체·위험도·사유·위치·이미지 URL·좌표·운행 상태 기록 | `hazards` SELECT |
| 월간 리포트 | GET | `/reports/monthly?childId={childId}&month=YYYY-MM` | 아이 UUID·조회 월 query 필수 | `200`, 탐지 총건수·물체별 집계·다음 단계 안내 | `children`, `hazards` SELECT |
| 기기 등록·아이 연결 | POST | `/devices` | `childId` UUID·`deviceId`·`name` | `201`, 기기·현재 상태. 동일 등록 재시도는 기존 기기 반환 | `devices` INSERT 또는 SELECT |
| 기기 상태 | GET | `/devices/{deviceId}/status` | 기기 식별 문자열 | `200`, 최근 보고 기준 상태. 없는 기기는 `404` | `devices` SELECT, 조회로 상태 갱신하지 않음 |
| 일시정지 요청 기록 | POST | `/devices/{deviceId}/commands/pause` | `{}`, `Idempotency-Key` UUID | 최근 온라인 보고 시 `202 REQUESTED`, 전달 `NOT_CONNECTED`. 미보고/오프라인 `409` | `operation_requests` INSERT 또는 재시도 SELECT |
| 명령 접수 조회 | GET | `/devices/{deviceId}/commands/{commandId}` | 기기 ID·요청 UUID | `200`, 접수 기록. 실행 확인 상태 `UNKNOWN`, 확인 시각 `null` | `operation_requests` SELECT |
| 직접 제거 재확인 요청 기록 | POST | `/hazards/{hazardId}/removal-checks` | `{}`, `Idempotency-Key` UUID | 활성 위험·아이 연결 일치·온라인/일시정지 보고 시 `202 UNKNOWN`, 전달 `NOT_CONNECTED` | `operation_requests` INSERT 또는 재시도 SELECT |
| 안전 처리 접수 조회 | GET | `/safety-actions/{actionId}` | 요청 UUID | `200 UNKNOWN`, 잔존 여부·완료 시각 `null`, 처리 `PENDING` | `operation_requests` SELECT |
| 재개 요청 차단 | POST | `/devices/{deviceId}/commands/resume` | `{}`, `Idempotency-Key` UUID | 미처리 위험 또는 안전 확인 미구현으로 `409` | 저장 안 함 |
| 이송 요청 차단 | POST | `/hazards/{hazardId}/relocations` | `{}` 또는 `safeZoneId`, `Idempotency-Key` UUID | 없는 위험 `404`, 미확정 정책 `409 RELOCATION_NOT_CONFIGURED` | 저장 안 함 |

### 아이 등록·성장단계 규칙

| 항목 | 현재 동작 |
| --- | --- |
| 이름 | 앞뒤 공백 제거, 필수, 최대 50자 |
| 생년월일 | 필수, 미래 날짜 거부 |
| PATCH | 이름·생년월일 중 최소 한 필드 필요 |
| 월령 | `Asia/Seoul` 기준으로 완료된 개월 수 계산, 현재 일자가 출생일보다 작으면 1개월 차감 |
| 동일 등록 재시도 | 같은 키·같은 입력이면 기존 아이 반환, 다른 입력이면 `409 IDEMPOTENCY_KEY_REUSED` |
| 동시 등록 재시도 | PostgreSQL 트랜잭션 잠금으로 같은 키 요청을 순서대로 처리. 아이·최초 프로필 이력을 한 번만 저장 |
| 수정 후 등록 재시도 | 새 등록은 최초 입력의 SHA-256 지문을 보존. 원래 등록 요청을 다시 보내도 현재 아이 정보를 반환하며 수정 내용을 되돌리지 않음 |

| 월령 | stage | status |
| --- | --- | --- |
| 0~11개월 | `INFANT` | `APPLIED` |
| 12~35개월 | `TODDLER` | `APPLIED` |
| 36~95개월 | `ACTIVE_CHILD` | `APPLIED` |
| 96개월 이상 | `null` | `UNSUPPORTED` |

월령은 별도 저장하지 않고 조회 시 계산한다. 월말·윤년의 일자 경계 정책과 실제 위험 기준은
추가 합의가 필요하다. 프로필 등록 상태는 기기 설정 전달·적용 확인까지 완료됐다는 의미가 아니다.
V4 이전에 등록된 아이는 최초 입력을 복원할 수 없으므로 지문을 소급 생성하지 않고 현재 이름·생년월일과 비교한다.

등록 요청 예시:

```http
POST /api/v1/children
Content-Type: application/json
Idempotency-Key: 91aad61d-09de-4b2d-9fb0-5e5df0c762cd

{"name":"테스트 아이","birthDate":"2024-03-15"}
```

### 대시보드와 위험 저장의 현재 범위

| 항목 | 현재 동작 |
| --- | --- |
| 기본 아이 선택 | 인증 연결 전까지 `childId` query로 명시적으로 선택. 접근 권한을 검증하는 값은 아님 |
| `device` | 아이에게 등록된 기기의 상태. 미등록이면 `null`, 등록만 한 경우 상태 `UNKNOWN`·배터리/보고 시각 `null` |
| `currentProfile` | DB 생년월일에서 계산한 상태·단계·월령 |
| `activeHazards` | 해당 아이의 `ACTIVE` 위험, 최신 감지순. 없으면 `[]` |
| `reportSummary` | 이번 달 `reportId`·`month`·`available: true`. 발행 완료가 아니라 빈 월을 포함한 조회 가능 여부 |
| 위험 목록 상태 | 현재 `ACTIVE`, `RESOLVED` 조회 지원 |
| 위험 저장 | `HazardService.recordDetection()` 내부 서비스 구현, 외부 POST API는 없음 |
| 저장 결과 | 새 위험은 `ACTIVE`로 저장. 위험도 판단·실제 로봇 정지를 수행하지 않음 |
| 입력 식별자 | 내부 저장 입력의 `eventId` 필수, 앞뒤 공백 제거 후 최대 100자. 기기가 같은 탐지를 재전송할 때 같은 값을 유지해야 함 |
| 탐지 재전송 | 같은 `deviceId`·`eventId`·내용이면 기존 위험 반환. 동시 요청도 한 건만 저장 |
| 이벤트 내용 충돌 | 같은 기기·이벤트의 내용이 다르면 `DETECTION_EVENT_REUSED` 충돌. 다른 이벤트는 별도 위험으로 저장 |
| 좌표 검증 | x·y는 함께 생략하거나 모두 유한한 0~1 값이어야 함. `NaN`·무한대 거부 |

`recordDetection()`은 향후 모델 결과 수신 API 또는 기기 메시지 소비자가 호출할 준비 코드다.
현재 자동 탐지 데이터 유입 경로는 없으며 샘플 위험 데이터를 생성하지 않는다.
기존 위험이 `RESOLVED`여도 재전송으로 다시 활성화하지 않는다. 내용 비교는 공백·시각의 시간대·좌표의
음수 0을 정규화한 최초 입력 지문으로 수행한다. 운행 상태 등 내용이 바뀐 보고는 같은 이벤트의 재전송으로 취급하지 않는다.
서로 다른 이벤트에서 같은 물체가 반복 감지된 경우를 하나로 묶는 기능은 아직 없다.

### 기기 등록·상태 저장 규칙

| 항목 | 현재 동작 |
| --- | --- |
| 연결 범위 | 개발용 잠정 정책: 아이 1명당 기기 1대, 기기 1대당 아이 1명. 기존 아이에게 자동으로 기기를 생성하지 않음 |
| 식별자 | `deviceId`는 기기 측에서 정한 1~100자 ID. 영문·숫자로 시작하고 영문·숫자·점·밑줄·콜론·하이픈 허용, 대소문자 구분 |
| 이름 | 앞뒤 공백 제거 후 1~100자. 실제 프론트에 등록 이름 표시 |
| 재시도 | 같은 ID·아이·이름은 기존 기기 반환. 같은 ID의 다른 아이/이름은 `409 DEVICE_ALREADY_REGISTERED` |
| 추가 연결 | 같은 아이의 다른 기기는 `409 CHILD_DEVICE_ALREADY_LINKED`. 기기 수정·연결 해제·재연결 API는 아직 없음 |
| 등록 의미 | DB의 연결 정보만 저장. 실제 페어링·기기 소유권 확인·네트워크 연결 완료를 뜻하지 않음 |
| 입력 경로 | `DeviceService.recordStatus()` 내부 서비스만 준비. 향후 인증된 메시지 소비자가 호출하며 외부 상태 입력 API 없음 |
| 입력값 | 기기 ID·연결/운행 상태·기기 보고 시각 필수, 배터리 선택(0~100). 미래 보고 시각 거부 |
| 보고 순서 | 이전 보고는 무시. 같은 시각·내용의 재전송은 그대로 반환하고 서버 수신 시각을 갱신하지 않음. 같은 시각의 다른 내용은 `DEVICE_STATUS_REPORT_REUSED` 충돌 |
| 시각 정밀도 | PostgreSQL에 맞춰 마이크로초로 비교·저장. 보고 시각 `last_reported_at`과 서버 수신 시각 `last_seen_at`을 분리 |
| 최근 상태 | 보고·수신 시각 모두 기본 300초 이내일 때만 상태·배터리 표시. 만료하면 연결/운행 `UNKNOWN`·배터리 `null`, 마지막 수신 시각은 유지 |
| 만료 정책 | `DEVICE_STATUS_MAX_AGE_SECONDS` 양수로 조정 가능, 변경 후 재시작. 300초는 잠정값이며 기기 보고 주기 확정 후 조정 |
| 오프라인 | 명시적인 최근 `OFFLINE` 보고만 오프라인 표시. 조회 실패·미보고·만료를 `OFFLINE`으로 바꾸지 않음. 온라인이 아니면 운행 상태는 `UNKNOWN` |
| 실제 제어 | `commandsAvailable: false`. 명령 전달·정지·재개 확인은 미구현이며 프론트 실제 제어 버튼 비활성화 |

등록 예시(실제 공유 DB에 저장되므로 사용할 아이 ID로 변경):

```http
POST /api/v1/devices
Content-Type: application/json

{"childId":"실제 아이 UUID","deviceId":"robot-001","name":"거실 로봇청소기"}
```

기존 위험 기록의 `device_id`에는 기기 FK를 소급 추가하지 않았다. 탐지 수신 통합 시 인증된 기기의
등록 ID·연결된 아이를 검증해야 한다. 현재 등록 API도 인증이 없으므로 로컬 개발에서만 사용한다.

### 명령·안전 처리 요청 기록의 현재 범위

| 항목 | 현재 구현 |
| --- | --- |
| 접수 의미 | 사용자 의도를 기록한 감사 이력이다. 실제 기기에 전달하는 큐·명령 실행이 아니며 `deliveryState: NOT_CONNECTED` 명시 |
| 실제 환경 | 신규 기기는 미보고 `UNKNOWN`이므로 접수 조건을 충족하지 못한다. 인증된 상태 수신 연동 전에는 정상적으로 `409` 차단 |
| 중복 방지 | 기기별 `Idempotency-Key` UUID UNIQUE, 같은 키·종류·위험 건은 같은 요청 ID 반환. 다른 종류/대상은 `409 IDEMPOTENCY_KEY_REUSED` |
| 동시 재시도 | 기존 기기 트랜잭션 잠금을 공유하여 같은 요청을 한 번만 저장. 명령과 안전 처리 사이의 키 재사용도 검사 |
| 재시도 조건 | 이미 접수된 같은 요청은 이후 상태가 만료돼도 기존 기록 반환. 새 요청의 온라인/일시정지 조건과 구분 |
| 일시정지 | 최근 온라인 보고를 요구하고 `REQUESTED` 저장. `devices.operation_state`를 PAUSED로 바꾸지 않음 |
| 직접 제거 재확인 | 활성 위험이 등록 기기의 아이·ID와 일치하고 최근 `ONLINE`·`PAUSED` 보고가 있어야 접수. 입력 버튼만으로 위험 해결·재개하지 않음 |
| 재확인 상태 | 모델에 확인을 시작한 것이 아니므로 `CHECKING` 대신 `UNKNOWN`, 잔존 여부 `null`, 처리 `PENDING` 저장/응답 |
| 조회 상태 | 명령·안전 처리로 확인된 운행 상태는 `UNKNOWN`, 확인/완료 시각 `null`. 현재 기기의 PAUSED 보고를 요청 성공 증거로 사용하지 않음 |
| 재개 | 아이의 활성 위험이 있으면 `HAZARD_UNRESOLVED`, 없어도 안전 확인 연동 전에는 `SAFETY_CONFIRMATION_REQUIRED`. 위험 0건을 안전 증거로 사용하지 않음 |
| 이송 | 안전 위치·가능 물체·배터리 조건·전달 방식 미정이므로 `RELOCATION_NOT_CONFIGURED`, 이동 중·임시 완료 기록 생성 안 함 |
| 완료·진행 이력 | 기기/모델 결과 수신·상태 전이·성공/실패 기록·이력 목록 API는 미구현. 타이머로 완료/실패를 추정하지 않음 |
| 프론트 | `commandsAvailable: false` 유지. 실제 제어·재확인 화면은 연동 전 안내를 유지하며 접수만으로 성공 표시하지 않음 |

향후 기기 전달 기능을 붙일 때 이 개발용 감사 기록을 자동 재생해서는 안 된다. 별도의 전달·결과 상관관계,
요청 만료·재시도·기기 인증 계약을 확정한 뒤 새 요청부터 연동한다.

### 월간 리포트 집계 규칙

| 항목 | 현재 동작 |
| --- | --- |
| 조회 월 | `YYYY-MM`, 0001년부터 현재 월까지. 미래 월·잘못된 형식은 `400` |
| 집계 범위 | 아이별 `detected_at`, 서울 시간 월초 이상·다음 월초 미만. `ACTIVE`·`RESOLVED` 모두 포함 |
| 총건수 | 해당 월에 저장된 위험 행 수. 같은 이벤트 재전송은 추가 집계되지 않음. 처리 완료·서로 다른 물체 개수가 아니며 다른 이벤트의 반복 감지 묶음은 미적용 |
| 물체별 집계 | 물체 종류·이름별 합산, 해당 그룹의 가장 높은 위험도. 건수 내림차순, 동률은 종류·이름순 |
| 빈 월 | `200`, 탐지 0건·물체 목록 `[]`. 조회 오류와 구분 |
| 성장단계 변경 | `stageChanges`에 선택 월의 실제 저장 이력 전체 제공. 기존 `stageChange`는 마지막 변경, 없으면 `null` |
| 기준 변경 | 단계·상태 변경 시 저장된 기준 문구를 `criteriaChanges`로 제공. 최초 등록은 변경으로 세지 않음 |
| 미수집 데이터 | 회피율·청소 면적·평가는 `null` |
| 다음 단계 안내 | 현재 등록된 생년월일과 백엔드 기준 문구 사용. 과거 월은 월말, 현재 월은 오늘 기준이며 실제 적용 이력이 아님 |
| 저장 방식 | 별도 리포트 테이블 없이 조회 시 집계. `report_{childUUID}_{YYYY-MM}`은 조회 식별자 |
| 과거 데이터 | 변경 당시 기준 문구는 스냅샷 보존. 아이 이름·다음 단계 안내·탐지 통계는 조회 시 계산하므로 응답이 달라질 수 있음 |

### 성장단계 변경 이력 저장 규칙

| 상황 | 저장 사유·동작 |
| --- | --- |
| 새 아이 등록 | `REGISTERED`: 최초 상태·단계·기준 문구 저장. 월간 리포트의 변경 목록에서는 제외 |
| 생년월일 수정으로 단계·상태 변경 | `BIRTH_DATE_UPDATED`: 이전/이후 상태·단계와 기준 문구 저장 |
| 월령 증가로 단계·상태 변경 | `AGE_CHANGED`: 매일 주기 실행 또는 프로필 상세·대시보드 조회, 수정·등록 재시도 시 현재 월령으로 갱신 |
| 단계·상태 그대로 | 이름만 수정, 같은 단계 내 생년월일 수정, 반복 조회는 이력 추가하지 않음 |
| 96개월 경계 | 지원 단계 → `UNSUPPORTED`·단계 `null` 전환도 기록. 생년월일 정정에 따른 지원 범위 복귀도 기록 |
| 저장 일관성 | 아이 프로필과 이력은 같은 트랜잭션. 동시 변경 충돌은 롤백하고 `409 CONFLICT` 반환 |
| 기존 아이 | 과거 이력 소급 생성 없음. 최초 변경의 이전 단계·상태는 기존 DB 값, 이전 기준 문구는 미수집 `[]` |
| 시각의 의미 | `changed_at`은 백엔드에서 실제 변경을 기록한 시각. 생일 경계 시각·기기 적용 확인 시각 아님 |

조회가 없던 기간도 서버가 실행 중이면 주기 실행으로 갱신한다. 서버 중단 등으로 놓친 기간의
중간 단계를 추측해 생성하지 않고, 다음 주기 실행 또는 조회·수정에서 DB의 이전 단계 → 현재 단계 한 건을 기록한다.
기준 문구만 코드에서 변경된 경우를 별도 버전 이벤트로 수집하는 기능도 후속 대상이다.

### 성장단계 주기 실행

| 항목 | 현재 동작 |
| --- | --- |
| 실행 | `ProfileRefreshJob`, 기본 매일 서울 시간 00:05. 실행 시각은 잠정값이며 설정 변경 가능 |
| 대상·조회 | 전체 아이 ID를 UUID 순서로 100명씩 조회. 전체 엔티티를 한꺼번에 메모리에 올리지 않음 |
| 저장 | 아이별 트랜잭션으로 `children` 갱신·`profile_history` 추가. 단계·상태가 같으면 저장하지 않음 |
| 월령 경계 | 12·36개월 단계 변경과 96개월 미지원 전환. 실제 계산은 기존 API와 같은 규칙 사용 |
| 실패 | 한 아이의 오류·동시 수정 충돌은 로그에 남기고 다음 아이 처리. 이후 조회 또는 다음 주기 실행에서 재평가 |
| 실행 로그 | `Profile refresh finished. checked=..., changed=..., failed=...` |
| 프론트 | 추가 API 호출 없음. 기존 대시보드·프로필·월간 리포트 조회에 저장 결과가 반영됨 |
| DB 변경 | 주기 실행 자체는 V3 구조 사용, 추가 테이블 없음. 실제 단계 변경이 발생할 때만 데이터 갱신 |

`backend/.env`에서 선택적으로 설정한다. 아래 기본값을 그대로 사용할 때는 추가 설정이 필요 없다.

```dotenv
PROFILE_REFRESH_ENABLED=true
PROFILE_REFRESH_CRON=0 5 0 * * *
```

Cron은 초·분·시·일·월·요일의 6필드다. 변경 후 백엔드를 재시작한다.
`PROFILE_REFRESH_ENABLED=false`는 주기 실행만 끄며, 조회·수정 시 프로필 갱신은 유지한다.
서버가 꺼져 있으면 실행하지 않는다. 놓친 실행을 재시작 즉시 재생하지 않으며 다음 예약 실행 또는 조회에서 갱신한다.
여러 백엔드 인스턴스를 운영할 때는 한 인스턴스만 주기 실행을 켜는 것을 권장한다. 분산 실행 잠금은 아직 없고,
동시 변경 시 기존 낙관적 잠금으로 이력·프로필 트랜잭션을 보호한다. 실제 기기에 설정을 전달·확인하는 기능은 아니다.

### 오류 응답

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "입력값을 확인해 주세요.",
    "requestId": "req_...",
    "fieldErrors": {"name":"이름은 필수입니다."}
  }
}
```

| HTTP 상태 | 주요 코드 | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필수값 누락·잘못된 UUID/날짜/상태·입력 검증 오류 |
| 404 | `CHILD_NOT_FOUND`, `HAZARD_NOT_FOUND`, `DEVICE_NOT_FOUND` | 아이·위험·기기 없음 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 방식. 프론트에서 기기 상태를 POST할 수 없음 |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `CONFLICT` | 등록 키 재사용·DB 무결성 충돌·동시 프로필 수정 충돌 |
| 409 | `DEVICE_ALREADY_REGISTERED`, `CHILD_DEVICE_ALREADY_LINKED` | 기존 기기 ID 또는 아이 연결 재사용 충돌 |
| 409 | `DEVICE_NOT_ONLINE`, `DEVICE_NOT_PAUSED` | 새 요청 접수에 필요한 최근 기기 상태를 확인할 수 없음 |
| 409 | `HAZARD_DEVICE_MISMATCH`, `HAZARD_ALREADY_RESOLVED` | 위험 건의 아이·기기 연결 불일치 또는 이미 해결된 위험 |
| 409 | `HAZARD_UNRESOLVED`, `SAFETY_CONFIRMATION_REQUIRED`, `RELOCATION_NOT_CONFIGURED` | 위험 미처리·안전 확인/이송 계약 미연동으로 실행 차단 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

오류 응답에는 `X-Request-Id` 헤더를 제공한다. `fieldErrors`는 오류 종류에 따라 `null`일 수 있다.
인증용 401·접근 권한용 403 처리는 아직 구현되지 않았다.

## 3. PostgreSQL 저장 구조

### 테이블 관계·마이그레이션

| 테이블 | 역할 | 관계 | 생성 이력 |
| --- | --- | --- | --- |
| `children` | 아이·프로필 저장 | 아이 하나에 여러 위험 건 연결 가능 | `V1__create_children.sql` |
| `hazards` | 위험 탐지 결과 저장 | `child_id → children.id`, 참조 중인 아이 삭제 제한 | `V2__create_hazards.sql` |
| `profile_history` | 백엔드 프로필 등록·변경 및 기준 스냅샷 | `child_id → children.id`, 참조 중인 아이 삭제 제한 | `V3__create_profile_history.sql` |
| `devices` | 등록 기기·아이 연결·최근 보고 상태 | `child_id → children.id`, UNIQUE로 1:1 연결, 참조 중인 아이 삭제 제한 | `V5__create_devices.sql` |
| `operation_requests` | 일시정지·직접 제거 재확인 접수 감사 기록 | 기기 FK, 재확인일 때 위험 FK. 실제 실행 큐 아님 | `V6__create_operation_requests.sql` |
| `flyway_schema_history` | 스키마 적용 이력 | Flyway 자동 관리 | V1~V6 성공 이력 |

`hazards.device_id`는 문자열이고 기기 테이블에 대한 외래키는 없다. 신규 기기 등록은 아이 존재·1:1 연결을
검증하지만 기존 내부 탐지 저장 서비스는 기기 연결을 검증하지 않는다. 실제 수신 통합 시 보완해야 한다.
이미 적용된 마이그레이션 파일은 수정하지 않고 다음 버전 파일을 추가한다.
`V4__add_storage_idempotency.sql`은 아래 입력 지문 컬럼과 기기·이벤트별 UNIQUE 인덱스를 추가한다.
지문은 재시도 내용 비교용이며 인증·암호화 목적이 아니다. 기존 행의 원본 입력·이벤트 ID는 소급 생성하지 않는다.

### `children`

| 컬럼 | PostgreSQL 타입 | 필수 | 저장 내용 |
| --- | --- | --- | --- |
| `id` | UUID | O | 아이 ID, PK |
| `name` | VARCHAR(50) | O | 이름 |
| `birth_date` | DATE | O | 생년월일 |
| `profile_status` | VARCHAR(20) | O | `APPLIED`, `UNSUPPORTED` |
| `stage` | VARCHAR(30) | - | `INFANT`, `TODDLER`, `ACTIVE_CHILD` 또는 NULL |
| `profile_applied_at` | TIMESTAMPTZ | 조건부 | 지원 프로필 적용 시각, 미지원이면 NULL |
| `registration_idempotency_key` | UUID | O | 등록 중복 방지 키, UNIQUE |
| `registration_input_hash` | VARCHAR(64) | 신규 등록 O | 최초 이름·생년월일의 SHA-256 지문, 수정 시 유지. 기존 행 NULL 허용 |
| `created_at`, `updated_at` | TIMESTAMPTZ | O | 등록·수정 시각 |
| `version` | BIGINT | O | JPA 낙관적 잠금 버전 |

DB 제약으로 빈 이름과 잘못된 상태·단계 조합을 제한한다. 생성 시각 내림차순 인덱스를 사용한다.
현재 단계명·점검 기준 문구의 정의는 백엔드 코드에 있다. 등록·단계 변경 당시 기준 문구는
`profile_history`에 별도로 보존한다.

### `hazards`

| 컬럼 | PostgreSQL 타입 | 필수 | 저장 내용 |
| --- | --- | --- | --- |
| `id` | UUID | O | 위험 ID, PK |
| `child_id` | UUID | O | 아이 ID, FK |
| `device_id` | VARCHAR(100) | O | 기기 식별 문자열 |
| `source_event_id` | VARCHAR(100) | 신규 저장 O | 기기가 유지하는 탐지 이벤트 ID. 기기 ID와 함께 UNIQUE, 기존 행 NULL 허용 |
| `detection_input_hash` | VARCHAR(64) | 신규 저장 O | 최초 탐지 입력의 SHA-256 지문. 기존 행 NULL 허용 |
| `status` | VARCHAR(30) | O | `ACTIVE`, `RESOLVED` |
| `object_type`, `object_name` | VARCHAR(50), VARCHAR(100) | O | 물체 종류·이름 |
| `risk_level` | VARCHAR(20) | O | `VERY_HIGH`, `HIGH`, `MEDIUM`, `LOW` |
| `risk_reason` | TEXT | - | 위험 판단 사유 |
| `detected_at` | TIMESTAMPTZ | O | 실제 감지 시각 |
| `location_label` | VARCHAR(200) | - | 감지 위치명 |
| `map_image_url`, `capture_image_url` | TEXT | - | 지도·캡처 이미지 URL |
| `marker_x`, `marker_y` | DOUBLE PRECISION | - | 이미지 기준 0~1 상대 좌표, 두 값 함께 저장 또는 함께 NULL |
| `device_operation_state` | VARCHAR(30) | O | 입력으로 전달받은 기기 운행 상태 기록 |
| `created_at`, `updated_at` | TIMESTAMPTZ | O | 기록 생성·수정 시각 |
| `version` | BIGINT | O | JPA 낙관적 잠금 버전 |

운행 상태 허용값은 `RUNNING`, `PAUSED`, `STOPPING`, `RESUMING`, `READY_TO_RESUME`, `UNKNOWN`이다.
이는 실시간 상태를 기기에 재확인한 결과가 아니다. 이미지 파일 자체는 DB에 저장하지 않는다.
기기·상태·감지 시각 및 아이·감지 시각 기준 인덱스를 사용한다.

### `profile_history`

| 컬럼 | PostgreSQL 타입 | 저장 내용 |
| --- | --- | --- |
| `id` | BIGINT IDENTITY | 기록 PK, 동일 시각의 기록 정렬에 사용 |
| `child_id` | UUID | 아이 FK |
| `from_status`, `to_status` | VARCHAR(20) | 이전·이후 프로필 상태. 최초 등록의 이전 상태만 NULL |
| `from_stage`, `to_stage` | VARCHAR(30) | 이전·이후 성장단계. 최초 등록·미지원 단계는 NULL |
| `reason` | VARCHAR(30) | `REGISTERED`, `AGE_CHANGED`, `BIRTH_DATE_UPDATED` |
| `changed_at` | TIMESTAMPTZ | 변경을 실제 기록한 시각 |
| `from_criteria`, `to_criteria` | JSONB | 당시 기준의 `code`·`title`·`description` 배열 |

아이별 최초 등록 기록은 최대 1건이며, 아이·시각·PK 인덱스로 월간 이력을 조회한다.
애플리케이션에서는 기존 이력을 수정하지 않고 추가만 한다. 실제 기기 적용 이력과는 다르다.

### `devices`

| 컬럼 | PostgreSQL 타입 | 저장 내용 |
| --- | --- | --- |
| `id` | VARCHAR(100) | 기기 ID, PK |
| `child_id` | UUID | 아이 FK·UNIQUE, 1:1 연결 |
| `name` | VARCHAR(100) | 표시 이름 |
| `connection_state` | VARCHAR(20) | 최근 입력의 `ONLINE`, `OFFLINE`, `UNKNOWN` |
| `operation_state` | VARCHAR(30) | 최근 입력의 운행 상태, 초기 `UNKNOWN` |
| `battery_percent` | INTEGER | 0~100, 미보고 NULL |
| `last_reported_at` | TIMESTAMPTZ | 기기 보고 시각, 미보고 NULL |
| `last_seen_at` | TIMESTAMPTZ | 새로운 보고를 서버가 수신한 시각, 미보고 NULL |
| `created_at`, `updated_at` | TIMESTAMPTZ | 등록·새로운 보고 저장 시각 |
| `version` | BIGINT | JPA 낙관적 잠금 버전 |

만료된 보고의 원본 DB 값은 보존하고 조회 응답에서만 `UNKNOWN`·배터리 `null`로 처리한다.
기기 보고 원본 전체 이력·명령·안전 처리 요청은 이 테이블에 저장하지 않는다.

### `operation_requests`

| 컬럼 | PostgreSQL 타입 | 저장 내용 |
| --- | --- | --- |
| `id` | UUID | 접수 요청 PK, commandId 또는 actionId |
| `device_id` | VARCHAR(100) | 등록 기기 FK |
| `hazard_id` | UUID | 직접 제거 재확인 대상 위험 FK, 일시정지는 NULL |
| `idempotency_key` | UUID | 기기별 요청 중복 방지 키, 기기 ID와 함께 UNIQUE |
| `kind` | VARCHAR(30) | `PAUSE`, `DIRECT_REMOVAL_CHECK` |
| `status` | VARCHAR(20) | 명령 접수 `REQUESTED`, 재확인 미연동 `UNKNOWN` |
| `created_at` | TIMESTAMPTZ | 서버 접수 시각 |

현재 접수 기록은 추가만 하며 수정하지 않는다. 재개·이송 차단 요청은 저장하지 않는다.
`NOT_CONNECTED`, 확인 운행 상태 `UNKNOWN`, 처리 `PENDING`, 잔존 여부/완료 시각 `null`은
현재 미연동 범위를 나타내는 응답이며 기기·모델이 실제 보고한 결과가 아니다.

### 실제 데이터와 임시 데이터

| 구분 | 저장 위치·현재 상태 |
| --- | --- |
| 실제 아이 정보 | PostgreSQL `children` |
| 실제 위험 정보 | PostgreSQL `hazards`; 모델 연결 전에는 자동으로 쌓이지 않음 |
| 실제 프로필 변경 이력 | PostgreSQL `profile_history`; 기능 적용 이후 등록·단계/상태 변경부터 저장 |
| 실제 기기 정보 | PostgreSQL `devices`; 명시적으로 등록한 기기만 저장. 하드웨어 미연동으로 자동 상태 보고 없음 |
| 실제 요청 정보 | PostgreSQL `operation_requests`; 접수 조건을 충족하는 요청만 저장, 자동 실행·임의 샘플 생성 없음 |
| 점검 당시 데이터 | 2026-09-18 12:45 기준 아이 6건·위험 0건·프로필 이력 1건·기기 0건·접수 요청 0건. 고정값이 아니며 사용에 따라 변경됨 |
| 선택한 아이 정보 | 브라우저 `sessionStorage`, 인증 연결 전 임시 복구용 |
| 예시 지도·위험 목록·리포트·이송 화면 상태 | 프론트 시안 데이터·메모리 상태, PostgreSQL에 저장하지 않음 |

읽기 전용 확인 SQL:

```sql
SELECT COUNT(*) FROM children;
SELECT COUNT(*) FROM hazards;
SELECT id, child_id, name, connection_state, operation_state, battery_percent, last_seen_at FROM devices;
SELECT id, device_id, hazard_id, kind, status, created_at FROM operation_requests ORDER BY created_at DESC;
SELECT child_id, from_stage, to_stage, reason, changed_at FROM profile_history ORDER BY changed_at, id;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

## 4. 프론트엔드 반영 사항

| 화면·기능 | API 연결에 따른 동작 |
| --- | --- |
| 아이 등록·수정 | 서버 저장 성공 후 아이 정보·재계산 프로필 반영 |
| 홈 | 대시보드 조회, 실제 API 모드에서 5초 간격 갱신 |
| 홈 데이터 상태 | 조회 중·성공·실패, 마지막 성공 응답 시각·수동 다시 조회 표시. 실패 시 기기 상태는 UNKNOWN, 이전 배터리/정지 상태를 현재 값처럼 표시하지 않음 |
| 아이 이름 동기화 | 서버의 아이 이름을 홈·sessionStorage에 반영. 프로필에서 수정 성공 시 이름·재계산된 단계/월령을 홈에도 즉시 반영 |
| 프로필 상세 | 서버 기준 표시, 로딩·실패·재시도·미지원 상태 구분 |
| 위험 알림·상세 | 대시보드 활성 위험 선택 시 동일 위험 상세 API 조회 |
| 위험 목록 | 백엔드 목록 API는 준비됨. 현재 프론트는 대시보드 `activeHazards` 사용 |
| 새로고침 | 아이 정보를 `sessionStorage`에서 임시 복구 |
| 연령 문구 | 0~11개월 / 12~35개월 / 36~95개월로 통일 |
| 월간 리포트 | 홈에서 진입·선택 월 재조회, 로딩·실패·재시도·0건 구분. 물체별 건수·차트 표시 |
| 프로필 변경 리포트 | 선택 월의 전체 단계 변경·사유·기록 시각 및 당시 기준 문구 표시. 이력 없는 월은 빈 안내 |
| 미수집 리포트 값 | 회피율·청소 면적 카드 숨김. 실제 API 모드의 평가 버튼은 숨김 |
| 기기 | 미등록이면 `null`, 등록 후 실제 이름·최근 상태 표시. 미보고/만료는 상태 확인 전, 실제 제어 버튼 비활성화 |
| API 오류 안내 | 등록·수정·프로필·대시보드·위험 상세·월간 리포트에 공통 오류 처리. 서버 message·requestId 표시, 입력 화면은 fieldErrors 반영 |

`VITE_API_BASE_URL` 미설정 시 프론트 서비스는 mock 모드로 동작한다.
실제 API 모드의 Safety Profile 상세는 서버 활성 위험을 사용하고 지도는 준비 중으로 표시한다.
예시 지도·임시 리포트는 mock 모드에서만 표시한다.

## 5. 미구현 기능과 알려진 제한

| 영역 | 아직 없는 기능 |
| --- | --- |
| 인증 | 로그인, 보호자-아이·기기 접근 권한, 서버 세션 기반 기본 아이 선택 |
| 모델·기기 입력 | 실제 탐지·위험도 판단, 결과 수신 경로·인증, 기기의 안정적인 이벤트 ID 생성. 내부 중복 저장 방지는 구현됨 |
| 기기 | 실제 페어링·소유권 확인·상태 수신 연동, 수정·연결 해제·재연결·다중 기기 정책. 등록·상태 조회/내부 저장은 구현됨 |
| 명령 | 실제 전달·정지/재개 확인·실행 결과 수신·재시도/만료 계약. 일시정지 접수 기록·조회는 구현됨, 재개는 차단 |
| 안전 처리 | 실제 재확인·이송·진행/결과 수신·위험 해결 상태 갱신. 직접 제거 재확인 접수 기록·조회는 구현됨, 이송은 차단 |
| 이미지 | 업로드·보관·URL 공급·지도 및 좌표 변환 |
| 리포트·프로필 | 기준 문구 버전 변경 이력, 실제 기기 적용 이력, 평가 저장, 회피율·청소 면적 산식 확정 |

위험 상세의 이송 완료 타이머는 mock 모드에서만 사용한다. 실제 API 모드는 미연결 상태를
안내하고 실제 안전 완료로 표시하지 않는다.
내부 저장 입력 검증·이벤트 재전송 방지는 구현됐지만, 실제 기기 메시지 공급·접근 권한·이벤트 생성 정책은 후속 대상이다.

## 6. 로컬 실행 및 검증

Java 21과 Node.js/npm이 필요하다. Gradle은 Wrapper를 사용하며, 현재 개발 서버 연결에
Docker나 로컬 PostgreSQL 실행은 필요하지 않다. 제공 DB 접근 가능한 네트워크가 필요하다.

| 연결 항목 | 현재 설정 |
| --- | --- |
| DB 서버 | `project-db-campus.smhrd.com:3310` |
| DB·사용자 | `campus_lgdx_2` |
| DB 비밀번호 | `backend/.env`의 `DB_PASSWORD`, 저장소에 포함하지 않음 |
| 백엔드 | `http://localhost:8080` |
| 프론트 허용 Origin | `http://localhost:5173`, `http://127.0.0.1:5173` |

저장소 루트에서 최초 설정:

```powershell
Copy-Item backend/.env.example backend/.env
Copy-Item frontend/.env.example frontend/.env.local
```

기존 설정 파일이 있다면 위 복사는 생략한다. `backend/.env`의 placeholder를 발급된
비밀번호로 직접 바꾼다. `.env`·`.env.local`은 Git에서 제외되며 프론트에 DB 비밀번호를 넣지 않는다.
`frontend/.env.local`의 `VITE_API_BASE_URL`은 `http://localhost:8080`이다. 프론트 코드가 `/api/v1`을
붙이므로 환경변수에는 `/api/v1`을 추가하지 않는다.

각각 별도 터미널에서 실행:

```powershell
# 터미널 1: 백엔드
cd backend
.\run-local.ps1
```

```powershell
# 터미널 2: 프론트
cd frontend
npm install
npm run dev
```

백엔드 시작 시 Flyway가 마이그레이션을 적용하고 JPA가 스키마를 검증한다.
제공 DB에 연결하는 실행이므로 실제 아이 등록·수정 및 단계가 바뀌는 조회·주기 실행은 공유 DB 데이터를 변경한다.
Vite가 5173 이외 포트를 사용하면 현재 CORS 허용값도 조정해야 한다.

검증 명령:

| 위치 | 명령 | 목적 |
| --- | --- | --- |
| `backend/` | `.\gradlew.bat clean test` | 성장단계·변경 이력·주기 실행·대시보드·위험·월간 집계·좌표·재시도 테스트. DB 통합 테스트는 기본 생략 |
| `frontend/` | `npm run build` | TypeScript·프로덕션 빌드 검증 |
| `frontend/` | `npm run lint` | 프론트 코드 검사 |
| `frontend/` | `node --test tests/apiError.test.mjs` | 서버 오류·요청 ID·비JSON 응답·요청 키 충돌 안내 검증 |

단위 테스트의 저장소는 Mockito mock을 사용한다. 실제 PostgreSQL 저장이나 하드웨어 통합 테스트를
대신하지 않는다. DB 통합 테스트는 `RUN_DB_TESTS=true`와 `DB_PASSWORD`가 설정된 경우에만 실행한다.
DB 테스트 클래스 모두 예약 실행을 끄고 자신이 만든 테스트용 아이만 변경한다.

| DB 테스트 | 검증 범위 | 테스트 데이터 정리 |
| --- | --- | --- |
| `ProfileHistoryDatabaseTests` | 등록·변경·JSONB 재조회·월 경계·동일 시각 정렬·자동 갱신의 월령 경계·ID 배치 | 각 테스트 트랜잭션 롤백 |
| `StorageIdempotencyDatabaseTests` | 아이·기기 동시 등록·탐지 재전송·명령/재확인 동시 접수, 수정 후 원본 등록 재시도, UNIQUE 제약, 월간 중복 집계 방지, 트랜잭션 잠금 | 실제 커밋 검증 후 고유 테스트 키로 만든 요청·아이·기기·위험·이력만 삭제 |
| `DeviceDatabaseTests` | 기기 등록·상태 저장/재조회·대시보드 반영·재전송·아이 연결 UNIQUE 제약 | 각 테스트 트랜잭션 롤백 |
| `OperationDatabaseTests` | 접수 저장·재조회·재시도·교차 종류 키 충돌·위험 연결 검증·위험 ACTIVE 유지·UNIQUE 제약 | 각 테스트 트랜잭션 롤백 |

스키마 적용 이력은 롤백하지 않는다. 기본 테스트 결과를 다시 실행하려면 환경변수 설정 후
`.\gradlew.bat test --rerun-tasks`를 사용한다.
위험 기록 0건 상태에서 전체 탐지→저장→화면 표시 흐름은 아직 실제 검증되지 않았다.

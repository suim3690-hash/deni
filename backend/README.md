# Safety Care 백엔드

아이 정보와 성장단계별 Safety Profile, 위험 탐지 결과의 저장·조회 기반을 제공한다.
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
| 스키마 관리 | Flyway V1·V2, JPA `ddl-auto=validate` |
| 실행·검증 | Gradle Wrapper, JUnit·Mockito |

```text
backend/
├── README.md
├── .env.example                 # 비밀번호 설정 예시
├── run-local.ps1                # 로컬 .env 로딩 및 서버 실행
└── src/
    ├── main/
    │   ├── java/com/deni/backend/
    │   │   ├── child/           # 아이·프로필·대시보드 API
    │   │   ├── hazard/          # 위험 저장 서비스·조회 API
    │   │   └── common/          # 공통 예외·오류 응답
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/    # V1 children, V2 hazards
    └── test/java/com/deni/backend/
        ├── child/
        └── hazard/
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

### 아이 등록·성장단계 규칙

| 항목 | 현재 동작 |
| --- | --- |
| 이름 | 앞뒤 공백 제거, 필수, 최대 50자 |
| 생년월일 | 필수, 미래 날짜 거부 |
| PATCH | 이름·생년월일 중 최소 한 필드 필요 |
| 월령 | `Asia/Seoul` 기준으로 완료된 개월 수 계산, 현재 일자가 출생일보다 작으면 1개월 차감 |
| 동일 등록 재시도 | 같은 키·같은 입력이면 기존 아이 반환, 다른 입력이면 `409 IDEMPOTENCY_KEY_REUSED` |

| 월령 | stage | status |
| --- | --- | --- |
| 0~11개월 | `INFANT` | `APPLIED` |
| 12~35개월 | `TODDLER` | `APPLIED` |
| 36~95개월 | `ACTIVE_CHILD` | `APPLIED` |
| 96개월 이상 | `null` | `UNSUPPORTED` |

월령은 별도 저장하지 않고 조회 시 계산한다. 월말·윤년의 일자 경계 정책과 실제 위험 기준은
추가 합의가 필요하다. 프로필 등록 상태는 기기 설정 전달·적용 확인까지 완료됐다는 의미가 아니다.

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
| `device` | 기기 구현 전이므로 `null` |
| `currentProfile` | DB 생년월일에서 계산한 상태·단계·월령 |
| `activeHazards` | 해당 아이의 `ACTIVE` 위험, 최신 감지순. 없으면 `[]` |
| `reportSummary` | 리포트 구현 전이므로 `null` |
| 위험 목록 상태 | 현재 `ACTIVE`, `RESOLVED` 조회 지원 |
| 위험 저장 | `HazardService.recordDetection()` 내부 서비스 구현, 외부 POST API는 없음 |
| 저장 결과 | 새 위험은 `ACTIVE`로 저장. 위험도 판단·실제 로봇 정지를 수행하지 않음 |

`recordDetection()`은 향후 모델 결과 수신 API 또는 기기 메시지 소비자가 호출할 준비 코드다.
현재 자동 탐지 데이터 유입 경로는 없으며 샘플 위험 데이터를 생성하지 않는다.

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
| 404 | `CHILD_NOT_FOUND`, `HAZARD_NOT_FOUND` | 아이 또는 위험 건 없음 |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `CONFLICT` | 등록 키 재사용 또는 DB 무결성 충돌 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

오류 응답에는 `X-Request-Id` 헤더를 제공한다. `fieldErrors`는 오류 종류에 따라 `null`일 수 있다.
인증용 401·접근 권한용 403 처리는 아직 구현되지 않았다.

## 3. PostgreSQL 저장 구조

### 테이블 관계·마이그레이션

| 테이블 | 역할 | 관계 | 생성 이력 |
| --- | --- | --- | --- |
| `children` | 아이·프로필 저장 | 아이 하나에 여러 위험 건 연결 가능 | `V1__create_children.sql` |
| `hazards` | 위험 탐지 결과 저장 | `child_id → children.id`, 참조 중인 아이 삭제 제한 | `V2__create_hazards.sql` |
| `flyway_schema_history` | 스키마 적용 이력 | Flyway 자동 관리 | V1·V2 성공 이력 |

`device_id`는 현재 문자열이고 기기 테이블에 대한 외래키는 없다. 기기 등록·아이-기기 관계 검증은
후속 구현 대상이다. 이미 적용된 V1·V2 파일은 수정하지 않고 스키마 변경 시 V3 이후 파일을 추가한다.

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
| `created_at`, `updated_at` | TIMESTAMPTZ | O | 등록·수정 시각 |
| `version` | BIGINT | O | JPA 낙관적 잠금 버전 |

DB 제약으로 빈 이름과 잘못된 상태·단계 조합을 제한한다. 생성 시각 내림차순 인덱스를 사용한다.
단계명·점검 기준 문구는 현재 백엔드 코드에 있으며 별도 DB 테이블에 저장하지 않는다.

### `hazards`

| 컬럼 | PostgreSQL 타입 | 필수 | 저장 내용 |
| --- | --- | --- | --- |
| `id` | UUID | O | 위험 ID, PK |
| `child_id` | UUID | O | 아이 ID, FK |
| `device_id` | VARCHAR(100) | O | 기기 식별 문자열 |
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

### 실제 데이터와 임시 데이터

| 구분 | 저장 위치·현재 상태 |
| --- | --- |
| 실제 아이 정보 | PostgreSQL `children` |
| 실제 위험 정보 | PostgreSQL `hazards`; 모델 연결 전에는 자동으로 쌓이지 않음 |
| 점검 당시 데이터 | 2026-09-18 기준 아이 2건·위험 0건. 고정값이 아니며 사용에 따라 변경됨 |
| 선택한 아이 정보 | 브라우저 `sessionStorage`, 인증 연결 전 임시 복구용 |
| 예시 지도·위험 목록·리포트·이송 화면 상태 | 프론트 시안 데이터·메모리 상태, PostgreSQL에 저장하지 않음 |

읽기 전용 확인 SQL:

```sql
SELECT COUNT(*) FROM children;
SELECT COUNT(*) FROM hazards;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

## 4. 프론트엔드 반영 사항

| 화면·기능 | API 연결에 따른 동작 |
| --- | --- |
| 아이 등록·수정 | 서버 저장 성공 후 아이 정보·재계산 프로필 반영 |
| 홈 | 대시보드 조회, 실제 API 모드에서 5초 간격 갱신 |
| 프로필 상세 | 서버 기준 표시, 로딩·실패·재시도·미지원 상태 구분 |
| 위험 알림·상세 | 대시보드 활성 위험 선택 시 동일 위험 상세 API 조회 |
| 위험 목록 | 백엔드 목록 API는 준비됨. 현재 프론트는 대시보드 `activeHazards` 사용 |
| 새로고침 | 아이 정보를 `sessionStorage`에서 임시 복구 |
| 연령 문구 | 0~11개월 / 12~35개월 / 36~95개월로 통일 |
| 기기·리포트 | 서버가 임의 수치를 만들지 않고 `null` 반환 |

`VITE_API_BASE_URL` 미설정 시 프론트 서비스는 mock 모드로 동작한다.
Safety Profile 상세의 지도·위험물 목록은 여전히 예시다.

## 5. 미구현 기능과 알려진 제한

| 영역 | 아직 없는 기능 |
| --- | --- |
| 인증 | 로그인, 보호자-아이·기기 접근 권한, 서버 세션 기반 기본 아이 선택 |
| 모델·기기 입력 | 실제 탐지·위험도 판단, 결과 수신 경로, 이벤트 식별키·중복 저장 방지 |
| 기기 | 등록·연결 상태·배터리·실시간 운행 상태 API |
| 명령 | 정지·재개 요청 저장·전달·실제 결과 조회 API |
| 안전 처리 | 직접 제거 재확인·이송·처리 결과 조회·위험 해결 상태 갱신 |
| 이미지 | 업로드·보관·URL 공급·지도 및 좌표 변환 |
| 리포트 | 월간 집계·조회·평가 저장, 회피율·청소 면적 산식 확정 |

특히 위험 상세의 이송 타이머는 아직 실제 API 모드에서도 완료 화면을 표시한다.
실제 안전 완료로 간주하지 말고 기기 연동 전에 mock 전용으로 분리해야 한다.
좌표 입력의 `NaN` 검증과 등록 요청의 동시 재시도 결과 처리도 추가 보완이 필요하다.

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
제공 DB에 연결하는 실행이므로 실제 아이 등록·수정은 공유 DB 데이터를 변경한다.
Vite가 5173 이외 포트를 사용하면 현재 CORS 허용값도 조정해야 한다.

검증 명령:

| 위치 | 명령 | 목적 |
| --- | --- | --- |
| `backend/` | `.\gradlew.bat clean test` | 성장단계·프로필·대시보드·위험 서비스 단위 테스트 |
| `frontend/` | `npm run build` | TypeScript·프로덕션 빌드 검증 |
| `frontend/` | `npm run lint` | 프론트 코드 검사 |

단위 테스트의 저장소는 Mockito mock을 사용한다. 실제 PostgreSQL 저장이나 하드웨어 통합 테스트를
대신하지 않는다. 위험 기록 0건 상태에서 전체 탐지→저장→화면 표시 흐름은 아직 실제 검증되지 않았다.

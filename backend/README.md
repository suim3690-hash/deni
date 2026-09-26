# backend — API·DB·로봇 통신

Java 21 · Spring Boot · Gradle · PostgreSQL · Flyway를 사용합니다. 프론트의 HTTP 요청을 처리하고, 로봇 명령·탐지 결과를 중계·저장합니다.

## 파일·폴더 안내

Java 패키지 경로의 기준은 `src/main/java/com/deni/backend/`입니다.

| 경로 | 역할 |
| --- | --- |
| `BackendApplication.java` | 서버 시작점 |
| `child/` | 아이 등록, 성장단계, 대시보드, 월간 리포트 |
| `device/` | 기기 등록·상태, WebSocket 명령, 탐지 업로드 |
| `hazard/` | 위험 조회·저장·처리 이력 |
| `operation/` | 제거 재확인·이송 요청과 결과 관리 |
| `common/` | 공통 오류, CORS, 중복 요청 방지, 시연 초기화 |
| `src/main/resources/application.properties` | 서버·DB·기기 설정 |
| `src/main/resources/db/migration/` | Flyway DB 변경 SQL |
| `src/test/` | 서비스·API·DB 테스트 |
| `run-local.ps1`, `.env.example` | 로컬 실행 스크립트와 환경변수 예시 |
| `build.gradle`, `gradlew.bat`, `gradle/` | 의존성·빌드·Gradle 실행 도구 |
| `scripts/smoke-api.ps1` | API 동작 점검 스크립트 |

## 실행

Java 21과 접속 가능한 PostgreSQL을 준비한 뒤 저장소 루트에서 실행합니다.

```powershell
cd backend
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# .env의 DB 접속 정보, ROBOT_DEVICE_ID, ROBOT_DEVICE_TOKEN 설정
powershell -ExecutionPolicy Bypass -File .\run-local.ps1
```

기본 주소는 `http://localhost:8080`, API 경로는 `/api/v1`입니다. 기기 ID·토큰은 하드웨어와 맞춥니다. 공용 DB와 한 기기에는 백엔드 한 인스턴스만 실행합니다. 재시작 시 기록 처리는 [시연 재시작 정책](../docs/시연_재시작_정책.md)을 확인하세요.

## 검증

```powershell
.\gradlew.bat test
```

DB 통합 테스트는 기본 제외됩니다. 공유 DB를 사용하는 검증 절차는 [통합 테스트](../docs/통합_테스트_시나리오.md)를 참고하세요 (`RUN_DB_TESTS=true`로 활성화).

[API 명세](../docs/API_명세서_프론트-백엔드.md) · [Socket 명세](../docs/Socket_명세서_백엔드-하드웨어.md) · [동작·DB 저장 규칙](../docs/화면_백엔드_동작_참고.md)

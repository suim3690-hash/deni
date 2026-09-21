# Deuni

아이의 성장단계에 맞는 안전 정보를 제공하고 로봇의 위험 탐지·제어 상태를 연결하는 프로젝트다.

## 프로젝트 구조

| 경로 | 담당·역할 |
| --- | --- |
| `frontend/` | React 화면, 백엔드 HTTP API 호출 |
| `backend/` | Java·Spring Boot API, PostgreSQL 저장, 하드웨어 통신 중계 |
| `hardware/` | 모델·로봇 실행 코드, 명령 수행과 상태·탐지 결과 전송 |
| `docs/` | 팀 사이의 API·Socket 계약과 통합 테스트 기준 |

목표 연결 구조는 `프론트 → HTTP → 백엔드 → WebSocket → 하드웨어`이며, 백엔드가 PostgreSQL에
요청과 결과를 저장한다. WebSocket 상태 수신·PAUSE 전달/결과와 HTTP 탐지 이미지 업로드가 구현됐다.
재개·이송·모델 재검사는 아직 미연결이다. 실행 예제와 지원 범위는 Socket 명세를 따른다.

## 버전과 브랜치

| 이름 | 용도 |
| --- | --- |
| `main` | 검증이 끝난 공용 기준 |
| 태그 `v1` | 하드웨어 Socket 통합 전 HTTP API·DB 기준점 |
| 브랜치 `v2` | 프론트–백엔드–하드웨어 통합 작업 |
| `frontend` | 프론트 작업 브랜치 |
| `backend` | 백엔드 작업 브랜치 |
| `hardware` | 하드웨어 작업 브랜치 |

Git은 `v2`와 `v2/hardware` 같은 이름을 동시에 만들 수 없으므로 담당 브랜치는 위 이름을 사용한다.

이전 버전을 별도 폴더로 복사하지 않고 Git 태그로 보관한다. 계약을 변경할 때는 코드와 `docs/` 문서를
같은 커밋 또는 같은 PR에서 수정한다. 비밀번호·기기 키·로컬 `.env`는 커밋하지 않는다.

## 통합 기준 문서

| 문서 | 역할 |
| --- | --- |
| [프론트–백엔드 API](docs/API_명세서_프론트-백엔드.md) | 화면의 HTTP 요청·응답 계약 |
| [백엔드–하드웨어 Socket](docs/Socket_명세서_백엔드-하드웨어.md) | 명령·상태·결과 메시지 계약 |
| [하드웨어 지원 기능](docs/하드웨어_지원기능.md) | 실제 구현 가능 범위와 결정 상태 |
| [통합 테스트](docs/통합_테스트_시나리오.md) | 세 영역을 함께 검증하는 완료 기준 |

하드웨어는 백엔드 HTTP/WebSocket을 통해 데이터를 보내며 DB에 직접 저장하지 않는다. 이전 직접 입력 방식은
[모델·하드웨어 DB 저장 가이드](docs/모델_하드웨어_DB_저장_가이드.md)에 참고용으로 보관한다.

## 실행

다른 Windows PC에서 프론트·백엔드·하드웨어를 함께 실행할 때는 [한 PC 통합 실행 가이드](docs/한_PC_통합_실행_가이드.md)를 따른다. Git에 포함되지 않는 DB 비밀번호·기기 키·모델 가중치를 별도로 준비해야 한다.

백엔드:

```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\run-local.ps1
```

프론트 실제 API 모드:

```powershell
cd frontend
npm install
npm run dev
```

프론트 mock 모드:

```powershell
cd frontend
npm run dev -- --mode mock
```

# Deuni

아이의 성장단계에 맞춰 위험을 알리고, 로봇의 탐지·정지·안전 이송을 연결하는 프로젝트입니다.

## 폴더 안내

| 경로 | 역할 | 시작 문서 |
| --- | --- | --- |
| `frontend/` | 보호자 화면: 아이 등록, 위험 확인, 로봇 제어, 월간 리포트 | [프론트 README](frontend/README.md) |
| `backend/` | HTTP API, 로봇 명령 중계, PostgreSQL 저장 | [백엔드 README](backend/README.md) |
| `hardware/` | Python 탐지 모델 실행, Pi 카메라·모터 연결 | [하드웨어 README](hardware/README.md) |
| `docs/` | 실행 가이드, API·Socket 명세, 테스트·기획 문서 | [문서 목록](docs/README.md) |
| `.gitignore` | 로컬 설정·빌드 결과 등 Git 제외 규칙 | — |

프론트는 HTTP로 백엔드에 요청합니다. 하드웨어는 백엔드와 WebSocket으로 명령·상태를 교환하고, HTTP로 탐지 사진을 올립니다. DB 저장은 백엔드가 담당합니다.

## 실행 순서

1. [한 PC 통합 실행 가이드](docs/한_PC_통합_실행_가이드.md)에 따라 DB 접속 정보, 기기 토큰, 모델 가중치를 준비합니다.
2. [백엔드](backend/README.md)를 실행합니다.
3. 실제 로봇을 사용할 때는 Pi 서버와 [하드웨어 런타임](hardware/README.md)을 실행합니다.
4. [프론트](frontend/README.md)를 실행합니다. 화면만 확인하려면 mock 모드를 사용합니다.

각 영역의 명령은 해당 README에 있습니다. 비밀번호·토큰·로컬 `.env`·모델 가중치는 Git에 올리지 않습니다.

## 현재 범위

전원·정지·직접 제거 재확인·수동 이송을 지원합니다. 자동 이송은 미구현이며 지도 마커는 실측 좌표가 아닙니다. 실제 API 모드는 오류가 나도 mock 데이터로 자동 전환하지 않습니다.

세부 동작은 [화면·백엔드 참고](docs/화면_백엔드_동작_참고.md), 장치 동작은 [런타임 가이드](hardware/CARE_RUNTIME_KO.md), 검증 절차는 [통합 테스트](docs/통합_테스트_시나리오.md)를 참고하세요.

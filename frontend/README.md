# 드니 Safety Care 프론트엔드

아이의 성장단계에 맞는 안전 정보를 보여주고, 로봇청소기의 위험 물체 감지·처리 상태를 확인하는 보호자용 화면입니다.
React 19 · TypeScript · Vite · Tailwind CSS 4로 만들었고, 백엔드는 HTTP API로만 호출합니다.

## 실행

```powershell
cd frontend
npm install
```

| 목적 | 명령 | 데이터 |
| --- | --- | --- |
| 실제 API 모드 | `npm run dev` | 백엔드(`VITE_API_BASE_URL`)와 DB의 실제 데이터 |
| mock 모드 | `npm run dev -- --mode mock` | 백엔드 없이 화면 예시 데이터 |
| 같은 네트워크 팀원에게 보여주기 | `npm run dev -- --mode mock --host --port 5174` | 팀원은 `http://내PC의IP:5174`로 접속 |
| 타입 검사 + 빌드 | `npm run build` | |
| 린트 | `npm run lint` | |

- 기본 포트는 5173입니다. 실제 API 모드는 백엔드가 5173 origin만 허용하므로 5173에서 여는 것을 권장합니다.
- 실제 API 모드는 백엔드가 먼저 실행 중이어야 합니다. 백엔드는 `backend/`에서 `powershell -ExecutionPolicy Bypass -File .\run-local.ps1`로 실행합니다.

## 환경변수

| 파일 | 내용 | 용도 |
| --- | --- | --- |
| `.env.example` | `VITE_API_BASE_URL=http://localhost:8080` | 복사해서 `.env.local`을 만드는 예시 |
| `.env.local` | `VITE_API_BASE_URL=http://localhost:8080` | 실제 API 모드 (개인 설정) |
| `.env.mock` | `VITE_API_BASE_URL=` (비움) | mock 모드 |

`VITE_API_BASE_URL`이 있으면 모든 화면이 실제 API를 호출하고, 비어 있으면 mock 데이터를 씁니다. 주소만 바꾸면 코드 수정 없이 연결됩니다.

## mock 화면 확인 방법

mock 모드에서 주소 뒤에 옵션을 붙이면 상황을 바꿔 볼 수 있습니다. 예: `http://localhost:5174/?mockHazard=none`

| 옵션 | 값 | 효과 |
| --- | --- | --- |
| `mockHazard` | 생략 또는 `swallow` | 삼킴 위험물(동전) 감지 상태 (기본값) |
| | `living` | 생활공간 위험요소(전선) 감지 상태 |
| | `none` | 위험물 없음 |
| | 그 밖의 이름 (예: `구슬`, `배터리`, `콘센트`) | 그 이름의 위험물 감지 상태 |
| `mockDevice` | `offline` / `unknown` | 로봇 미연결 상태 |
| | `paused` | 일시 정지 상태 |
| `mockStageChange` | (값 없이 붙임) | 리포트에서 모든 달에 걸음마 시기 → 유아 활동기 전환을 강제로 표시 (디자인 확인용) |
| `mockRedetect` | `1` | 직접 제거 후 첫 확인에서 위험 물체가 다시 감지되는 상황 |

옵션은 페이지를 처음 열 때 읽으므로 바꾼 뒤에는 새로고침해야 합니다. 아이 정보는 브라우저 탭의 `sessionStorage`에 저장되어, 탭을 닫으면 아이 등록부터 다시 시작합니다.

**mock에서 처리 흐름 보는 순서**
1. 홈에서 전원 버튼(`전원 켜고 ThinQ 연결`)을 눌러 연결합니다. 연결된 뒤 다시 누르면 꺼집니다.
2. `스마트 안심 케어 맵` 버튼을 눌러 지도 화면으로 이동합니다.
3. `위험 물체 안전 이송` → 마커가 안전한 장소로 이동 → `위험물을 처리했습니다` + `청소 재개` → `로봇청소기 작동 중`
4. 또는 `사용자 직접 제거` → `위험 물체 제거 완료` → 확인 → 작동 중

## 화면과 폴더

| 파일 | 화면 |
| --- | --- |
| `src/pages/ChildRegistration.tsx` | 아이 정보 등록 |
| `src/pages/RegisteredHome.tsx` | 홈 (기기 상태, 위험 알림, Safety Profile, 리포트 진입) |
| `src/pages/HazardLocation.tsx` | 스마트 안심 케어 맵 (위험 상세와 안전 처리를 한 화면에 통합) |
| `src/pages/SafetyProfileDetail.tsx` | 성장단계별 Safety Profile |
| `src/pages/GrowthReport.tsx` | 월간 안전 리포트 (성장단계, 감지 통계) |
| `src/components/` | 공용 컴포넌트 (위험 알림 박스, 성장단계 카드 등) |
| `src/services/` | 백엔드 API 호출과 mock 데이터 (`dashboard`, `children`, `reports`, `operations`) |
| `src/lib/` | 성장단계·위험도 기준 (`stages.ts`, `hazardRisk.ts`) |

## 백엔드 연동

- 홈은 5초마다 대시보드(`GET /api/v1/dashboard`)와 로봇 상태(`GET /api/v1/devices/{id}/robot-state`)를 다시 조회합니다.
- 지도 화면이 열려 있는 동안에는 위험 상세(`GET /api/v1/hazards/{id}`)도 5초마다 다시 조회해 마커가 서버 좌표를 따라 움직입니다.
- 감지 사진은 위험 상세의 `captureImageUrl`을 그대로 씁니다. 없거나 불러오지 못하면 "감지 사진을 불러오지 못했어요"를 표시합니다. 목업 사진은 mock에서만 나옵니다.
- **CORS:** 백엔드는 `APP_CORS_ALLOWED_ORIGINS`에 등록된 origin만 받습니다. 기본값은 `http://localhost:5173`, `http://127.0.0.1:5173`이고, 다른 주소로 열려면 백엔드 환경변수에 추가해야 합니다.
- 사용하는 API 전체 목록과 요청·응답은 [API 명세](../docs/API_명세서_프론트-백엔드.md)를 따릅니다.

## 아직 서버 지원이 없는 기능

| 기능 | 화면 동작 |
| --- | --- |
| 전원 끄기 | mock은 꺼지고, 실제 모드는 "아직 지원되지 않아요" 안내만 표시 |
| 청소 재개 | 버튼은 있으나 백엔드가 재개 요청을 아직 거부해 서버 오류 메시지를 표시 |
| 안전 이송·재검사 결과 | 접수까지만 되고, 서버가 완료 값을 주면 화면이 바뀌도록 연결해 둠 (결과 조회 3초 주기) |
| 위험 위치 마커 | 서버가 좌표를 저장한 위험만 표시. 하드웨어 업로드로 생긴 위험은 좌표가 없어 마커가 없음 |
| 위험 해제 | 백엔드에 위험을 해제하는 경로가 없어 한 번 감지된 위험이 홈에 계속 남음 |
| 자동 이송 모드 | 토글을 누르면 "아직 지원되지 않아요" 안내 |

## 관련 문서

- [프론트–백엔드 API 명세](../docs/API_명세서_프론트-백엔드.md)
- [하드웨어 지원 기능](../docs/하드웨어_지원기능.md)
- [통합 테스트 시나리오](../docs/통합_테스트_시나리오.md)
- [루트 README](../README.md)

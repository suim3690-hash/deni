# frontend — 보호자 화면

React · TypeScript · Vite · Tailwind CSS로 만든 웹 화면입니다. 백엔드 HTTP API로 아이 정보·위험·로봇 상태를 조회합니다.

## 파일·폴더 안내

| 경로 | 역할 |
| --- | --- |
| `src/main.tsx`, `src/App.tsx` | 앱 시작과 화면 전환 |
| `src/pages/` | 아이 등록, 홈, 위험 위치, 안전 프로필, 성장 리포트 화면 |
| `src/components/` | 헤더, 위험 알림, 성장단계 이력 등 공용 UI |
| `src/services/` | 아이·대시보드·안전 처리·리포트 API와 오류 처리 |
| `src/lib/` | 성장단계·위험도 계산, 실행 모드, ID 생성 |
| `src/assets/`, `public/` | 화면 이미지·아이콘·정적 파일 |
| `src/index.css` | 공통 스타일 |
| `tests/` | 위험도·알림·오류 처리 테스트 |
| `.env.example`, `.env.mock` | API 설정 예시와 mock 모드 설정 |
| `package.json`, `vite.config.ts`, `tsconfig*.json` | 실행 명령·의존성·빌드 설정 |

## 실행

저장소 루트에서 아래 명령을 실행합니다. Node.js와 npm이 필요합니다.

```powershell
cd frontend
npm ci
npm run dev
```

화면 주소는 기본 `http://localhost:5173`, API 주소는 `http://localhost:8080`입니다. API 주소를 바꾸려면 `.env.local`에 `VITE_API_BASE_URL`을 설정하고 재시작합니다. 다른 PC에서 접속할 때는 백엔드의 `APP_CORS_ALLOWED_ORIGINS`도 설정합니다.

백엔드 없이 화면만 확인하려면 개발 서버를 아래 명령으로 실행합니다.

```powershell
npm run dev -- --mode mock
```

일반 실행은 mock으로 자동 전환하지 않습니다. 지도는 임의 좌표이며 자동 이송 토글은 안내용입니다.

## 검증

```powershell
npm run build
npm run lint
node --test tests/*.test.mjs
```

[화면 동작·mock 옵션](../docs/화면_백엔드_동작_참고.md) · [API 명세](../docs/API_명세서_프론트-백엔드.md) · [통합 실행](../docs/한_PC_통합_실행_가이드.md)

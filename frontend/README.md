# 프론트엔드

React 19 · TypeScript · Vite · Tailwind CSS. 백엔드 HTTP API를 조회하고 사용자 명령을 전달하는 보호자 화면이다. DB나 로봇 WebSocket에는 직접 접속하지 않는다.

## 실행

```powershell
cd frontend
npm ci                         # 최초 또는 의존성 변경 시
npm run dev                    # 실제 API: 기본 http://localhost:5173
npm run dev -- --mode mock      # 백엔드 없이 UI 확인
```

실제 API에는 `.env.local`의 `VITE_API_BASE_URL=http://localhost:8080`이 필요하다. `.env.example`을 복사해 만들며 변경 후 Vite를 재시작한다. 다른 PC에서 열면 프론트 주소를 백엔드 `APP_CORS_ALLOWED_ORIGINS`에 추가한다.

| 화면 | 실제 API 모드에서 하는 일 |
| --- | --- |
| 아이 등록·프로필 | 아이 등록/수정, 월령·성장단계·안전 기준 표시 |
| 홈 | 대시보드·로봇 상태를 5초마다 갱신, 최신 전원 상태에 따라 ON/OFF 명령 |
| 스마트 안심 케어 맵 | 위험 상세·물체별 바운딩박스 크롭 사진 조회, 삼킴 위험 우선 표시, 건별 선택 |
| 안전 처리 | 직접 제거 재확인 또는 수동 안전 이송 요청 후 기기 결과를 조회해 완료 표시 |
| 직접 제거 재감지 | 제거 확인 요청 뒤 같은 위험이 다시 감지되면 서버의 최신 재감지 사진으로 교체 |
| 생활공간 위험 | `위험 요소 확인 완료`를 서버에 저장해 처리하고 알림·지도 마커 제거 |
| 월간 리포트 | 서버의 월별 감지·성장단계 이력 표시 |

삼킴 위험도는 영아기 `HIGH` → 걸음마 `VERY_HIGH` → 유아 활동기 `MEDIUM`이다. 생활공간 위험은 `HIGH` → `HIGH` → `VERY_HIGH`이다. 동시에 여러 건이면 **홈 알림 한 개에 건수를 보여주고**, 삼킴 위험부터 선택한다. 지도 사진은 선택한 위험 건의 서버 이미지만 사용하며 새 상세 데이터가 도착하기 전에는 이전 사진을 숨긴다.

| 구분 | 현재 제한 |
| --- | --- |
| 자동 이송 모드 | 토글 안내만 제공하는 목업. 기기에 자동 명령을 보내지 않음 |
| 지도 마커 | 실제 좌표가 없으면 예시 위치. 한 번에 선택한 위험의 마커만 표시 |
| 같은 이름의 여러 물체 | 백엔드에서 한 `ACTIVE` 위험으로 합치므로 개별 사진·마커·처리를 구분할 수 없음 |
| 생활공간 위험 확인 | 확인 완료 시 DB 위험을 `RESOLVED`로 처리하고 활성 지도에서 제거 |
| 새 브라우저 | 선택 아이는 `sessionStorage`에만 기억. 계정 기반 목록·로그인은 없음 |

목업은 `?mockHazard=swallow|living|none`, `?mockDevice=offline|unknown|paused`, `?mockRedetect=1` 등으로 상태를 바꿔 확인할 수 있다. 실제 서버 데이터와 섞이지 않는다.

```powershell
npm run build
npm run lint
node --test tests/*.test.mjs
```

API 계약은 [프론트–백엔드 명세](../docs/API_명세서_프론트-백엔드.md), 전체 실행은 [한 PC 통합 실행](../docs/한_PC_통합_실행_가이드.md)을 참고한다.

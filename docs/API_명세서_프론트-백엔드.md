# 프론트–백엔드 HTTP API (현재 구현)

기본 주소 `http://백엔드주소:8080/api/v1`. 프론트는 HTTP만 사용하며 로봇 명령·탐지는 백엔드가 [하드웨어 계약](Socket_명세서_백엔드-하드웨어.md)으로 중계한다. 본 문서는 현재 코드의 주요 계약만 다룬다.

## 1. 조회·등록

| 기능 | Method · 경로 | 주요 입력 → 결과 |
| --- | --- | --- |
| 아이 등록 | `POST /children` | `Idempotency-Key: UUID`, `{name,birthDate}` → `childId`, 성장단계. 동일 이름·생년월일은 데모 프로필 재사용 |
| 아이 수정 | `PATCH /children/{childId}` | `{name?,birthDate?}` → 갱신된 프로필 |
| Safety Profile | `GET /children/{childId}/safety-profile` | 월령·단계·기준. 지원 밖은 `UNSUPPORTED` |
| 홈 | `GET /dashboard?childId=UUID` | 아이·현재 단계·기기·`activeHazards`·리포트 요약 |
| 월간 리포트 | `GET /reports/monthly?childId=UUID&month=YYYY-MM` | 감지 건수·물체별 집계·프로필 변경 이력. 측정 불가 수치는 `null` |
| 기기 등록 | `POST /devices` | `{childId,deviceId,name}` → 등록 기기 |
| 활성 아이 변경 | `POST /devices/{deviceId}/active-child` | `{childId}` → 기기 상태. 이후 새 탐지는 이 아이에게 귀속 |
| 기기 상태 | `GET /devices/{deviceId}/status` | `connectionState`, `operationState`, `commandsAvailable` 등 |
| 최신 로봇 상태 | `GET /devices/{deviceId}/robot-state` | 전원·작업·이동·시간·거리·`stale`. 보고가 오래되면 UNKNOWN |
| 위험 목록 | `GET /hazards?deviceId=...&status=ACTIVE` | `{items:[...]}`. `RESOLVED` 조회도 가능 |
| 위험 상세 | `GET /hazards/{hazardId}` | 분류·이름·위험도·감지 시각·사진 URL·위치·상태·`acknowledgedAt` |
| 원본 탐지 목록 | `GET /devices/{deviceId}/detections` | 최신 50건의 `eventId`, 모델, 라벨, 시각, 이미지 URL |
| 원본 이미지 | `GET /devices/{deviceId}/detections/{eventId}/image` | 저장된 JPEG/PNG 바이트 |

## 2. 사용자 제어·위험 처리

| 요청 | Method · 경로 | 완료 조건 |
| --- | --- | --- |
| 전원·정지·재개 | `POST /devices/{deviceId}/commands/{power-on\|power-off\|pause\|resume}` | `Idempotency-Key: UUID`, 본문 `{}`. `202`는 접수일 뿐. 결과는 아래 GET으로 조회 |
| 명령 결과 | `GET /devices/{deviceId}/commands/{commandId}` | `status: REQUESTED/SUCCEEDED/FAILED/EXPIRED/UNKNOWN`, `deliveryState`·기기 결과 |
| 직접 제거 재확인 | `POST /hazards/{hazardId}/removal-checks` | 키·본문 `{}`. 기기가 선택 라벨의 2초 연속 부재를 보고해야 해결 |
| 수동 안전 이송 | `POST /hazards/{hazardId}/relocations` | 키·본문 `{}`. 대상 한 개·마커 확인 후 기기 완료 보고가 필요 |
| 안전 처리 결과 | `GET /safety-actions/{actionId}` | `treatmentStatus`가 `COMPLETED` 또는 `TEMPORARY_COMPLETED`일 때만 완료 표시 |
| 생활공간 위험 확인 | `POST /hazards/{hazardId}/acknowledgements` | `acknowledgedAt` 저장, 위험은 `ACTIVE` 유지. 미처리 삼킴 위험이 있으면 `409` |

제어 요청은 최근 기기 온라인·Socket 연결·유효한 모터 상태를 요구한다. 직접 제거/이송은 전원 ON과 실제 정지 상태도 확인한다. 일반 `resume`은 활성 삼킴 위험이 있으면 거부한다. 결과 미수신 `UNKNOWN`을 성공으로 간주하지 않으며, 처리 중 다른 위험 건으로 대상 전환을 막는다. 실제 처리 완료 후 로봇의 주행 여부는 최신 상태로 별도 표시한다.

## 3. 위험도와 동시 감지

| 성장단계 | 삼킴 위험 (`SWALLOW`) | 생활공간 위험 (`LIVING`) |
| --- | --- | --- |
| 영아기, 0~11개월 | HIGH | HIGH |
| 걸음마, 12~35개월 | VERY_HIGH | HIGH |
| 유아 활동기, 36~95개월 | MEDIUM | VERY_HIGH |

지원 라벨은 동전·구슬·배터리·주사위(`SWALLOW`), 전선·콘센트(`LIVING`)다. 백엔드가 성장단계로 위험도를 계산하며, V13은 기존 `ACTIVE`·`RESOLVED` 삼킴 위험도 현재 아이 단계로 재계산한다.

| 여러 건일 때 | 현재 규칙 |
| --- | --- |
| 홈 알림 | 카드 하나에 미확인 건수·대표 위험을 표시. 삼킴 위험 우선, 분류 안에서는 최신 감지 우선 |
| 상세·이미지 | 선택한 위험 한 건의 이미지·마커만 표시. 사진은 여러 바운딩 박스가 있는 동일 프레임일 수 있음 |
| 같은 물체 이름 | 원본 이벤트는 각각 저장하지만 같은 기기·아이·분류·이름의 `ACTIVE` 위험은 병합 |
| 직접 제거 | 같은 라벨이 하나라도 보이면 미완료. 다른 위험은 남겨 두고 정지 유지 |
| 수동 이송 | 같은 라벨 대상이 여러 개면 거부. 다른 라벨 위험은 별도로 처리 |
| 생활공간 위험 | 삼킴 위험 처리 후 확인. 확인해도 팝업만 숨기고 지도에는 계속 표시 |

## 4. 오류·지원 범위

일반 API 오류는 `{"error":{"code":"...","message":"...","requestId":"...","fieldErrors":{...}}}` 형식이고 `X-Request-Id`를 반환한다. 유효성 오류는 주로 `400`, 인증이 필요한 **하드웨어 업로드**는 실패 시 `401`, 대상 없음은 `404`, 현재 상태에서 실행 불가는 `409`다. 보호자 로그인·소유권 검증은 아직 없다.

자동 이송 모드, 실측 지도 좌표, 생활공간 위험 제거 확인/해제, ThinQ 실제 연결은 미구현이다. 웹 푸시 알림도 없다. 프론트의 화면 알림을 OS 푸시로 해석하지 않는다. 실제 실행·검증 범위는 [통합 현황](현황_2026-09-22_통합연결.md)과 [테스트 시나리오](통합_테스트_시나리오.md)를 따른다.

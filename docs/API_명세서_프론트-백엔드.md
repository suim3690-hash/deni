# 드니 Safety Care 프론트–백엔드 API 명세서

> 협의안 v0.1 · 2026-09-17  
> 대상: [3차 화면 설계](https://www.figma.com/design/VRe73HbPynTknNZkfMNuAp/%EA%B8%B0%ED%9A%8D-%EB%A9%98%ED%86%A0%EB%A7%81?node-id=1103-789)의 아이 등록, 홈, Safety Profile, 위험 상세·안전 처리, 월간 리포트  
> **주의:** 아래 경로와 JSON 필드에는 구현된 API와 미구현 제안 계약이 함께 포함돼 있다. 실제 구현 완료 목록과 DB 구조·제한은 [백엔드 README](../backend/README.md)를 기준으로 확인한다.

## 1. 구현 기준

- 기본 경로는 `/api/v1`, 요청·응답은 JSON을 사용한다. ID는 클라이언트가 해석하지 않는 문자열이다.
- 모든 사용자 API는 인증된 보호자와 해당 아이·기기·위험 건의 접근 관계를 서버에서 확인한다. 인증 방식과 토큰 전달 방식은 별도 합의한다.
- 시각은 시간대가 포함된 ISO 8601 문자열, 생년월일은 `YYYY-MM-DD`, 조회 월은 `YYYY-MM` 형식이다.
- 성장단계는 최신 사용자 결정인 `0~11개월 / 12~35개월 / 36~95개월(만 3~7세)`로 판정한다. `96개월 이상`에는 임의의 Safety Profile을 적용하지 않는다. 월령 계산의 기준 시간대·월말·윤년 규칙은 확정이 필요하다. 현재 일부 기존 문서의 `12~23개월` 표기는 이 최신 결정과 다르다.
- 로봇 제어 요청은 비동기다. `202 Accepted`와 `REQUESTED`, `CHECKING`, `MOVING` 응답만으로 성공 화면을 표시하지 않는다. 서버에서 실제 기기 결과와 저장 결과를 확인해 최종 상태를 제공한다.
- 아이 등록, 정지·재개, 직접 제거 재확인, 안전 위치 이동 요청에는 `Idempotency-Key: <UUID>`를 사용한다. 같은 사용자 동작의 재시도에는 같은 키를 사용해 중복 생성·중복 명령을 막는다.
- 화면은 서버 상태를 진실로 사용한다. 새로고침·재진입·연결 복구 시 저장된 위험 건, 처리 건, 실제 기기 상태를 다시 조회한다.

### 공통 오류 형식

```json
{
  "error": {
    "code": "HAZARD_UNRESOLVED",
    "message": "위험물이 아직 감지되어 청소를 재개할 수 없습니다.",
    "requestId": "req_123",
    "fieldErrors": null
  }
}
```

| HTTP 상태 | 화면 처리 |
| --- | --- |
| `400` | 입력 오류를 해당 필드에 표시. 예: 이름 누락, 미래 생년월일, 잘못된 월 형식 |
| `401` / `403` | 인증 필요 / 해당 아이·기기·위험 건 접근 불가를 구분 |
| `404` | 조회한 대상이 없어 현재 화면에서 재조회 또는 이전 화면 이동 안내 |
| `409` | 현재 상태에서 명령 불가. 예: 미처리 위험물이 남은 상태의 운행 재개 |
| `429` / `5xx` | 요청 제한·일시적 서버 실패 안내. 결과를 모르는 제어 명령은 새 명령을 무조건 재발행하지 않고 상태부터 조회 |

주요 오류 코드는 `VALIDATION_ERROR`, `DEVICE_OFFLINE`, `HAZARD_UNRESOLVED`, `ACTION_IN_PROGRESS`, `BATTERY_LOW`, `ACTION_RESULT_UNKNOWN`, `ACCESS_DENIED`로 제안한다. 같은 `Idempotency-Key`의 재전송은 별도 명령으로 처리하거나 요청 제한 오류를 내지 않고 최초 처리 결과를 반환한다.

## 2. 버튼별 API 목록

`GET` 행은 버튼 자체가 서버 상태를 바꾸는 것이 아니라 **이동한 화면에 필요한 데이터를 읽는 요청**이다.

| 화면·버튼 | HTTP 경로 | 주요 입력 | 주요 출력 | 프론트 처리 |
| --- | --- | --- | --- | --- |
| 아이 등록 `정보 등록하고 Safety Care 시작하기` | `POST /children` | 이름, 생년월일 | 아이 ID, 프로필 적용 상태·단계 | 제출 중 중복 클릭 방지 → 성공 시 다음 화면, 실패 시 입력 유지 |
| 등록 실패 `다시 시도하기` | `POST /children` | 동일 입력·동일 `Idempotency-Key` | 최초 요청의 저장 결과 | 중복 아이 생성 없이 결과 확인 |
| 홈 진입·새로고침 | `GET /dashboard` | 인증 연결 전에는 임시 `childId` query | 현재 아이·기기, 연결·운행 상태, 최근 위험 알림, 리포트 요약 | 온라인/오프라인·배터리·위험 카드 표시. 조회 실패를 오프라인으로 단정하지 않음 |
| 홈 상태 자동 갱신·명령 후 확인 | `GET /devices/{deviceId}/status` | 기기 ID | 연결 상태, 실제 운행 상태, 배터리, 확인 시각 | 저장된 화면 상태가 아닌 실제 기기 상태로 갱신 |
| 홈 `일시 정지` | `POST /devices/{deviceId}/commands/pause` | 요청 식별키 | 명령 ID, `REQUESTED` | 실제 정지 확인 후 `일시 정지` 표시 |
| 홈 `다시 시작` | `POST /devices/{deviceId}/commands/resume` | 요청 식별키 | 명령 ID 또는 `409` | 위험물 미처리 시 재개 거부. 실제 운행 확인 후 `작동 중` 표시 |
| 홈 Safety Profile `상세 보기` | `GET /children/{childId}/safety-profile` | 아이 ID | 월령, 적용 단계, 안전 기준 | 조회 결과로 프로필 화면 구성 |
| 아이 정보 `수정 완료` | `PATCH /children/{childId}` | 이름, 생년월일 | 수정된 아이 정보·재계산 프로필 | 저장 성공 후 프로필 재표시. 실패 시 편집값 유지 |
| 홈 `실시간 위험 감지 맵` | `GET /hazards?deviceId={deviceId}&status=ACTIVE` | 기기 ID, 상태 | 진행 중 위험 건과 맵 마커 목록 | 맵에 현재 위험 건을 표시. 항목 선택 시 상세 GET |
| 홈 알림 `위치 확인하기`·맵의 위험 항목 선택 | `GET /hazards/{hazardId}` | 위험 건 ID | 객체·위험도·위치·시간·이미지·판단 사유·맵 좌표 | 선택한 동일 위험 건의 상세 표시 |
| `위험물을 치웠어요` | `POST /hazards/{hazardId}/removal-checks` | 요청 식별키 | 처리 ID, `CHECKING` | 로봇 정지 유지·`재확인 중` 표시. 최종 결과 조회 |
| `안전 위치로 이동` | `POST /hazards/{hazardId}/relocations` | 요청 식별키, 필요 시 안전 위치 ID | 처리 ID, `MOVING` | `이동 중` 표시. 실제 이동 완료 후에만 임시 완료 표시 |
| 재확인·이동 결과 갱신 | `GET /safety-actions/{actionId}` | 처리 ID | 처리 상태, 위험물 잔존 여부, 저장 상태, 실제 로봇 상태 | `완료 / 잔존 / 임시 완료 / 실패·미확정`으로 분기 |
| 기기 명령 결과 갱신 | `GET /devices/{deviceId}/commands/{commandId}` | 명령 ID | 명령 상태, 실제 로봇 상태 | 정지·재개 명령의 실제 결과 확인 |
| 홈 `리포트 보러가기`·조회 월 `선택 완료` | `GET /reports/monthly?childId={childId}&month=YYYY-MM` | 아이 ID, 선택 월 | 현재 Figma 리포트의 집계·성장 변화·기준 변화·예고 | 선택 월 데이터 표시. 빈 월과 조회 실패 구분 |
| 리포트 `좋아요 / 보통이에요 / 아쉬워요` | `POST /reports/{reportId}/feedback` | 평가값 | 저장된 평가 | 평가를 서버에 보존하는 경우에만 호출 |

호출 예시의 실제 URL은 위 경로 앞에 `/api/v1`을 붙인다. 예: `GET /api/v1/dashboard`.

## 3. 요청·응답 상세

### 3.1 아이 등록·수정과 Safety Profile

#### `POST /api/v1/children`

```json
{ "name": "김튼튼", "birthDate": "2024-03-15" }
```

성공 `201 Created`:

```json
{
  "childId": "child_123",
  "name": "김튼튼",
  "birthDate": "2024-03-15",
  "safetyProfile": {
    "status": "APPLIED",
    "stage": "TODDLER",
    "ageMonths": 30,
    "appliedAt": "2026-09-17T10:00:00+09:00"
  }
}
```

`safetyProfile.status`: `APPLIED | UNSUPPORTED`. 등록 자체가 성공했더라도 적용할 연령 밖이면 `UNSUPPORTED`, `stage: null`과 안내용 정보를 반환하고, 임의의 단계를 지정하지 않는다. 서버 검증 실패는 성공 응답으로 포장하지 않는다.

#### `PATCH /api/v1/children/{childId}`

요청은 등록과 같은 `{ name, birthDate }` 중 변경할 필드만 전달한다. 성공 응답은 수정된 아이 정보와 **재계산된** `safetyProfile`을 포함한다. 기존 프로필을 프론트에서 월령만 바꿔 재사용하지 않는다.

#### `GET /api/v1/children/{childId}/safety-profile`

```json
{
  "childId": "child_123",
  "status": "APPLIED",
  "stage": "TODDLER",
  "stageLabel": "걸음마 시기",
  "ageMonths": 30,
  "criteria": [
    { "code": "CHOKING", "title": "삼킴 위험 감지", "description": "승인된 단계별 기준 설명" }
  ],
  "appliedAt": "2026-09-17T10:00:00+09:00"
}
```

`stage`: `INFANT | TODDLER | ACTIVE_CHILD | null`. 위험 대상 객체와 기준 문구는 프론트에서 임의로 산출하지 않고 서버가 승인된 기준으로 제공한다.

### 3.2 홈·기기 상태와 정지/재개

#### `GET /api/v1/dashboard`

현재 로그인 보호자의 MVP 기본 아이·기기를 조회한다. 여러 아이·기기 선택 정책은 추후 확정한다.
현재 인증이 구현되지 않은 개발 단계에서는 다른 테스트 아이와 데이터가 섞이지 않도록
`GET /api/v1/dashboard?childId={childId}`로 호출한다. 인증과 보호자-아이 관계가 구현되면
서버 세션에서 기본 아이를 식별하고 이 임시 query는 제거한다.

```json
{
  "child": { "childId": "child_123", "name": "김튼튼" },
  "device": {
    "deviceId": "device_123",
    "connectionState": "ONLINE",
    "operationState": "PAUSED",
    "batteryPercent": 82,
    "lastSeenAt": "2026-09-17T10:14:00+09:00"
  },
  "currentProfile": { "status": "APPLIED", "stage": "TODDLER", "ageMonths": 30 },
  "activeHazards": [
    {
      "hazardId": "hazard_123",
      "objectName": "레고 브릭",
      "riskLevel": "VERY_HIGH",
      "locationLabel": "거실 러그 위",
      "detectedAt": "2026-09-17T10:14:00+09:00"
    }
  ],
  "reportSummary": { "reportId": "report_2026_09", "month": "2026-09", "available": true }
}
```

`connectionState`: `ONLINE | OFFLINE | UNKNOWN`; `operationState`: `RUNNING | PAUSED | STOPPING | RESUMING | READY_TO_RESUME | UNKNOWN`. 상태 조회 오류는 `OFFLINE` 값으로 대체하지 않는다.

`GET /api/v1/devices/{deviceId}/status`는 위 `device` 객체와 같은 연결·운행 상태, 배터리, `lastSeenAt`을 반환한다. 홈의 주기적 갱신과 명령 후 실제 상태 확인에 사용한다. 기기가 등록되지 않았다면 `dashboard.device`는 `null`로 반환하고 별도 안내를 표시한다.

#### `POST /api/v1/devices/{deviceId}/commands/pause` / `resume`

요청 본문은 `{}`로 두고 `Idempotency-Key` 헤더를 보낸다. 성공 접수 시 `202 Accepted`:

```json
{ "commandId": "command_123", "status": "REQUESTED" }
```

`GET /api/v1/devices/{deviceId}/commands/{commandId}`:

```json
{
  "commandId": "command_123",
  "status": "SUCCEEDED",
  "deviceOperationState": "PAUSED",
  "confirmedAt": "2026-09-17T10:14:01+09:00"
}
```

`status`: `REQUESTED | SUCCEEDED | FAILED | UNKNOWN`. `resume`은 위험물 미처리·재확인 미완료면 `409 HAZARD_UNRESOLVED`를 반환해야 한다. 직접 제거 경로에서는 서버가 안전 확인 뒤 재개를 수행하므로 프론트가 별도의 `resume`을 중복 호출하지 않는다. 안전 위치 이동 후의 자동 재개 여부는 미정이다. 현재 `청소 재개 가능`은 상태 표시이며, 향후 실제 `청소 재개` 버튼을 만들면 이 `resume` API를 사용한다.

### 3.3 위험 상세

#### `GET /api/v1/hazards?deviceId={deviceId}&status=ACTIVE`

`200 OK` 응답은 `{ "items": [...] }` 형식이다. 각 항목에는 `hazardId`, `objectName`, `riskLevel`, `detectedAt`, `location.label`, `location.marker`를 포함한다. 위험 건이 없으면 `items: []`를 반환한다. 선택한 항목은 아래 상세 API로 다시 조회한다.

하드웨어·탐지 모델 통합 전에는 외부용 위험 등록 API를 노출하지 않는다. 백엔드는 향후 기기 메시지
소비자가 호출할 저장 서비스와 조회 API만 제공하며, 임의의 샘플 탐지 데이터를 생성하지 않는다.

#### `GET /api/v1/hazards/{hazardId}`

```json
{
  "hazardId": "hazard_123",
  "deviceId": "device_123",
  "status": "ACTIVE",
  "object": { "type": "TOY_PART", "name": "레고 브릭" },
  "riskLevel": "VERY_HIGH",
  "riskReason": "현재 아이의 성장단계에서 삼킴 위험이 있는 크기의 물체입니다.",
  "detectedAt": "2026-09-17T10:14:00+09:00",
  "location": {
    "label": "거실 러그 위",
    "mapImageUrl": "https://example.invalid/maps/map_123.png",
    "marker": { "x": 0.34, "y": 0.42 }
  },
  "captureImageUrl": "https://example.invalid/captures/hazard_123.png",
  "deviceOperationState": "PAUSED"
}
```

맵 좌표 `x`, `y`는 이미지 가로·세로에 대한 `0~1` 상대값으로 **제안**한다. 서버와 기기가 다른 좌표계를 쓴다면 변환 계약을 확정한다. 이미지·위치만 누락된 경우 해당 필드는 `null`로 주고 나머지 위험 정보는 유지한다. 권한 없는 요청에는 부분 정보도 반환하지 않는다. 예시 URL은 실제 서비스 주소가 아니다.

### 3.4 직접 제거·안전 위치 이동

#### `POST /api/v1/hazards/{hazardId}/removal-checks`

보호자가 실제 위험물을 치운 뒤 `위험물을 치웠어요`를 누를 때만 호출한다. 본문 `{}`, `Idempotency-Key` 필수. `202 Accepted`:

```json
{ "actionId": "action_123", "type": "DIRECT_REMOVAL_CHECK", "status": "CHECKING" }
```

서버는 해당 기기에 위험물 부재 재확인을 요청한다. **버튼 클릭이나 요청 접수만으로 `처리 완료` 저장 또는 운행 재개를 하지 않는다.**

#### `POST /api/v1/hazards/{hazardId}/relocations`

보호자가 `안전 위치로 이동`을 선택할 때 호출한다. 안전 위치를 서버에 미리 지정한다면 본문 `{}`, 사용자가 선택한다면 `{ "safeZoneId": "zone_123" }`를 사용한다. `Idempotency-Key` 필수. `202 Accepted`:

```json
{ "actionId": "action_456", "type": "RELOCATION", "status": "MOVING" }
```

기기 오프라인, 이동 불가 물체, 배터리 부족 등은 성공으로 응답하지 않는다. 이동 명령 요청만으로 완료를 기록하지 않는다.

#### `GET /api/v1/safety-actions/{actionId}`

프론트는 요청 후 이 경로를 재조회하거나 동일 필드의 서버 이벤트를 구독한다. 직접 제거 성공 예시:

```json
{
  "actionId": "action_123",
  "type": "DIRECT_REMOVAL_CHECK",
  "status": "COMPLETED",
  "hazardPresent": false,
  "treatmentStatus": "COMPLETED",
  "deviceOperationState": "RUNNING",
  "completedAt": "2026-09-17T10:16:00+09:00"
}
```

위험물이 남은 경우:

```json
{
  "actionId": "action_123",
  "type": "DIRECT_REMOVAL_CHECK",
  "status": "HAZARD_REMAINS",
  "hazardPresent": true,
  "treatmentStatus": "PENDING",
  "deviceOperationState": "PAUSED",
  "completedAt": null
}
```

안전 위치 이동 완료 예시:

```json
{
  "actionId": "action_456",
  "type": "RELOCATION",
  "status": "TEMPORARY_COMPLETED",
  "hazardPresent": null,
  "treatmentStatus": "TEMPORARY_COMPLETED",
  "deviceOperationState": "READY_TO_RESUME",
  "completedAt": "2026-09-17T10:16:00+09:00"
}
```

직접 제거 상태는 `CHECKING | HAZARD_REMAINS | RESUMING | COMPLETED | FAILED | UNKNOWN`, 이동 상태는 `MOVING | TEMPORARY_COMPLETED | FAILED | UNKNOWN`을 사용한다. 직접 제거의 `COMPLETED`는 **위험물 부재 확인·처리 기록 저장·실제 운행 재개 확인**을 모두 충족한 상태다. `UNKNOWN`은 실제 기기 상태를 다시 확인하기 전 성공 또는 실패로 확정하지 않는다.

### 3.5 월간 리포트와 평가

최신 사용자 결정에 따라 **현재 Figma 리포트 구성**을 기준으로 한다. 기존 PRD의 ‘실제 안전 처리 내역’ 필수 표시와 `회피율·청소 면적·만족도 제외` 문구는 현재 시안과 다르므로, 백엔드가 집계 구현에 착수하기 전에 범위 변경을 문서에도 반영해야 한다.

#### `GET /api/v1/reports/monthly?childId={childId}&month=YYYY-MM`

```json
{
  "reportId": "report_2026_09",
  "childId": "child_456",
  "month": "2026-09",
  "childName": "김튼튼",
  "stageChange": {
    "from": "TODDLER",
    "to": "ACTIVE_CHILD",
    "changedAt": "2026-09-01T00:00:00+09:00"
  },
  "summary": {
    "detectionCount": 12,
    "avoidanceRatePercent": 100,
    "safeCleanedAreaSquareMeters": 824.5
  },
  "detectionsByObject": [
    { "objectType": "TOY_PART", "label": "레고 브릭", "count": 3, "riskLevel": "VERY_HIGH" }
  ],
  "criteriaChanges": [
    { "title": "가구 모서리 감지 활성화", "description": "적용된 안전 기준의 변경 설명" }
  ],
  "nextStagePreview": { "stage": null, "description": "다음 지원 단계 안내 또는 지원 범위 밖 안내" },
  "feedback": null
}
```

조회 월에 기록이 없어도 `200 OK`로 `stageChange: null`, 집계값 `0`, 목록 `[]`을 반환한다. 조회 실패는 별도 오류 응답을 반환한다. 회피율의 분자·분모, 안전 청소 면적의 산출 기준 및 데이터 공급 여부는 아직 확정되지 않았다. 실제 산출할 수 없는 값은 `null`로 응답하고 프론트에서 수치 카드를 숨기거나 준비 중 상태로 표시한다. 임의의 숫자를 내려주지 않는다.

#### `POST /api/v1/reports/{reportId}/feedback`

평가를 서버에 저장하기로 결정한 경우에만 구현한다.

```json
{ "rating": "GOOD" }
```

`rating`: `GOOD | NEUTRAL | BAD`. 성공 `200 OK`:

```json
{ "reportId": "report_2026_09", "rating": "GOOD", "updatedAt": "2026-09-17T10:20:00+09:00" }
```

## 4. 화면 안에서만 처리하는 버튼과 미정 기능

| 화면 요소 | 처리 |
| --- | --- |
| 뒤로 가기, 팝업 닫기, `취소`, 월 선택창 열기, 연도·월 임시 선택 | 화면 상태만 변경. 선택 월 `선택 완료` 시 리포트 GET 호출 |
| 위험 상세 `직접 제거` | 제거 안내 화면으로 이동만 수행. 실제 API 요청은 `위험물을 치웠어요`에서 수행 |
| 재감지 팝업 `확인했어요` | 팝업을 닫고 직접 제거 안내로 돌아감. 재확인 API를 자동 반복하지 않음 |
| `재확인 중`, `이동 중`, `임시 안전조치 완료`, `작동 중` | 서버 상태를 표시하는 요소. 프로토타입의 시간 기반 전환은 실제 API 완료 조건이 아님 |
| 홈 `우회 청소` | 위험물이 남아 있는 상태에서 운행을 재개하는 API로 연결하지 않음. 동작 정의 전 구현 보류 |
| `위험물 자동 이송 모드` 토글 | 보호자 선택 없이 이동하는 규칙·대상 물체·실패 처리 미정. 이번 핵심 API에서 제외 |
| `물체 설정`, 상단 `+`·설정·알림 종, 기타 서비스 탭 | 화면 목적과 저장할 데이터가 정의된 뒤 API 추가 |

위험 감지·위험도 판단·즉시 정지·푸시 발송은 사용자가 버튼을 눌러 시작하는 API가 아니다. 기기와 서버가 자동으로 수행하고, 프론트는 결과를 대시보드와 위험 상세 API로 조회한다.

## 5. 백엔드 협의가 필요한 결정

1. 인증 방식, 기본 아이·기기 선택 방식, 다중 아이·기기 지원 범위.
2. 월령 계산의 기준 시간대·월말·윤년 규칙과 성장단계 위험 기준표. 최신 지원 구간은 `0~11 / 12~35 / 36~95개월`이다.
3. 로봇의 위험물 부재 재확인 방법, 이동 가능한 물체와 지정 안전 위치, 이동 성공 확인, 실제 운행 재개 확인 방식.
4. 안전 위치 이동 후 청소를 자동 재개할지, 보호자에게 별도의 `청소 재개` 버튼을 제공할지.
5. 위험 상세 이미지 공급 방식·만료 시간, 맵 이미지와 좌표 변환 방식.
6. 리포트의 회피율·안전 청소 면적 산식과 원천 데이터, 평가의 서버 저장 여부. 현재 Figma 리포트 범위와 기존 문서의 차이도 함께 확정.
7. 처리 결과 조회를 폴링으로 제공할지 서버 이벤트로 제공할지, 결과 미확정·기록 저장 실패 시 복구 계약.

## 6. 근거

- [3차 화면 설계](https://www.figma.com/design/VRe73HbPynTknNZkfMNuAp/%EA%B8%B0%ED%9A%8D-%EB%A9%98%ED%86%A0%EB%A7%81?node-id=1103-789)
- [기능 요구사항](기능요구사항명세서.md) · [PRD](PRD_MVP_ThinQ_Kids_SafeGuard.md) · [메뉴 구조도](메뉴구조도.md)
- 이후 대화에서 확정된 연령대 변경, 직접 제거 후 기기 재확인, 홈 온라인·오프라인 표시, 월간 리포트의 Figma 시안 유지 결정

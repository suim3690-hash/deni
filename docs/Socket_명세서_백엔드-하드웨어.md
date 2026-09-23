# 백엔드–하드웨어 통신 계약

현재 통합 런타임은 **WebSocket으로 명령·상태·결과**, **HTTP로 탐지 사진**을 보낸다. 로봇 PC가 DB에 직접 접속하지 않는다. 동일 기기에는 로봇 런타임 하나와 백엔드 인스턴스 하나만 사용한다.

| 항목 | 값 |
| --- | --- |
| WebSocket | `ws://백엔드IP:8080/ws/devices` |
| 이미지 업로드 | `POST http://백엔드IP:8080/api/v1/hardware/detections` |
| 인증 | `Authorization: Bearer {ROBOT_DEVICE_TOKEN}`, `X-Device-Id: {ROBOT_DEVICE_ID}` |
| 사전 조건 | `POST /api/v1/devices`로 기기 등록, 양쪽 ID·토큰 일치(토큰 32자 이상) |
| 보안 범위 | 개발망용. TLS·보호자 인증·기기별 독립 토큰 관리는 후속 과제 |

## 1. WebSocket 메시지

기기 메시지의 공통 형식은 `{"type":"...","messageId":"UUID","deviceId":"...","sentAt":"시간대 포함 ISO-8601","payload":{...}}`이다. 서버가 유효한 메시지를 받으면 같은 `messageId`로 `RECEIPT`와 `accepted:true`를 돌려준다. 거부 시 `ERROR`를 보낸다.

| 방향 | `type` | 주요 `payload` |
| --- | --- | --- |
| 기기 → 백 | `ROBOT_STATE` | `operationState`, `movementState`, `sampledAt`, 선택적 `batteryPercent`, `movementDurationMs`, `movementDistanceM`, `powerEnabled`, `taskState` |
| 백 → 기기 | `COMMAND` | `commandId`, `command`, `expiresAt`, `parameters` |
| 기기 → 백 | `COMMAND_ACK` | `commandId`, `status:"DELIVERED"` |
| 기기 → 백 | `COMMAND_RESULT` | `commandId`, `status:"SUCCEEDED"/"FAILED"`, `operationState`, `completedAt`, 선택적 실패 코드·안전 처리 증거 |

| 명령 `command` | `parameters` | 성공 증거 |
| --- | --- | --- |
| `POWER_ON`, `POWER_OFF`, `PAUSE`, `RESUME` | `{}` | OFF/PAUSE는 `PAUSED`, RESUME은 `RUNNING`. POWER_ON은 실제 모터 확인 후 상태 보고 |
| `RECHECK_HAZARD` | `hazardId`, `objectLabel` | 같은 `hazardId`, `hazardPresent:false`, `absenceDurationMs>=2000` |
| `RELOCATE` | `hazardId`, `objectLabel` | 대상 1개 확보·마커 추적·후진·물체/마커 근접 재확인 후 같은 `hazardId`, `relocationCompleted:true` |

`ROBOT_STATE.operationState`는 `RUNNING/PAUSED/RELOCATING/UNKNOWN`, `movementState`는 `FORWARD/TURNING/BACKWARD/STOPPED/UNKNOWN`을 사용한다. 시간·거리·배터리는 미측정 시 `null`이다. 대체 추정값으로 성공을 꾸미지 않는다. 과거 상태는 최신 상태를 덮어쓰지 않는다.

수동 이송의 `taskState`는 `ALIGNING_TARGET → CAPTURING → SEEKING_MARKER → PUSHING_TO_MARKER → BACKING → VERIFYING_DROP → TURNING_AROUND` 순서다. 마커가 보이지 않을 때는 전진하지 않으며, 배치 확인 실패 결과에는 `relocationCompleted`를 넣지 않는다. 이송 중 같은 라벨 대상은 `VERIFYING_DROP`의 로컬 판정에는 계속 쓰지만 새 탐지 이벤트로 업로드하지 않는다. 다른 라벨 위험은 그대로 업로드한다.

명령은 백엔드의 DB 대기열에서 전달하며 `commandId`로 중복을 막는다. 전송 전 만료는 `EXPIRED`, 전송 후 결과 미수신은 `UNKNOWN`이다. `UNKNOWN`은 성공이 아니며 자동 재전송하지 않는다. 기기는 만료된 명령을 실행하지 않고 동일 ID의 결과를 재사용해야 한다. 최신 `PAUSED` 상태만으로 과거 명령의 성공을 추정하지 않는다. 안전 처리 요청은 실제 `COMMAND_RESULT`의 증거를 확인한 뒤 위험을 해결한다.

## 2. 탐지 HTTP 업로드

| multipart 필드 | 규칙 |
| --- | --- |
| `eventId` | UUID. 같은 원본의 재시도는 같은 ID |
| `modelType` | `HAZARD` 또는 `OBJECT` |
| `objectLabel` | 하드웨어가 매핑한 라벨, 최대 100자 |
| `image` | 바운딩 박스 JPEG/PNG 실제 바이트, 최대 5MiB |

응답은 `{"eventId":"...","hazardId":"... 또는 null"}`이다. 같은 ID로 다른 원본을 보내면 `409`다. 최초 수신 시각은 DB가 기록한다. 같은 프레임에 여러 물체가 있으면 각 바운딩박스에 여백을 둔 **물체별 크롭 사진과 서로 다른 eventId**를 보낸다. 지원 라벨이면 백엔드가 아이의 현재 프로필로 위험을 생성·갱신한다.

| 성장단계 | 삼킴 위험(동전·구슬·배터리·주사위) | 생활공간 위험(전선·콘센트) |
| --- | --- | --- |
| 영아기 | HIGH | HIGH |
| 걸음마 | VERY_HIGH | HIGH |
| 유아 활동기 | MEDIUM | VERY_HIGH |

지원하지 않는 라벨이나 연령 밖은 원본만 저장하고 `hazardId=null`이다. 현재 가중치에는 주사위 클래스가 없다. 삼킴 위험은 모델 내부 숫자 점수와 무관하게 로봇에서 정지 대상으로 다룬다. 생활공간 위험은 알림만 보낸다. 자동 이송은 목업이며 수동 `RELOCATE`만 연결된다.

실행 방법은 [하드웨어 README](../hardware/README.md), HTTP 화면 계약은 [API 명세](API_명세서_프론트-백엔드.md)를 따른다.

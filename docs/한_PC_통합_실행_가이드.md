# 한 PC 통합 실행 가이드

Windows PC 한 대에서 프론트·백엔드·`care_runtime.py`를 실행하고, Pi에는 카메라/모터 서버를 별도로 실행하는 개발·시연 구성이다.

```text
브라우저/React :5173 --HTTP--> Spring Boot :8080 --SQL--> 공유 PostgreSQL :3310
                                 ↕ WebSocket 명령·상태 / ← HTTP 탐지 이미지
                           PC care_runtime.py --카메라·모터--> Pi
```

## 1. 코드 외 준비물

| PC 설정 | 위치·주의 |
| --- | --- |
| Java 21, Node.js/npm, Python 3.13 | 설치 후 각 명령에서 버전 확인 |
| DB 비밀번호 | `backend/.env`의 `DB_PASSWORD`만 설정. 하드웨어에는 불필요 |
| 기기 ID·토큰 | 백엔드 `.env`와 하드웨어 실행 터미널에서 같은 값 사용 |
| 프론트 API 주소 | `frontend/.env.local`에 `VITE_API_BASE_URL=http://localhost:8080` |
| 모델 가중치 | Git 외 별도 전달, `hardware/weights/object.pt`, `hazard.pt` |
| Pi 주소·인증 | 실행 환경에서 전달. 코드·Git에 비밀번호 저장 금지 |

기존 저장소에서는 `git switch v2` 후 `git pull --ff-only origin v2`를 사용한다. 로컬 수정이 있으면 강제 초기화하지 않는다. `.env` 파일과 가중치는 Git으로 전달되지 않는다. 기존 로컬 파일을 덮어쓰지 말고, 없는 경우에만 `.env.example`에서 만든다.

```powershell
if (-not (Test-Path backend/.env)) { Copy-Item backend/.env.example backend/.env }
if (-not (Test-Path frontend/.env.local)) { Copy-Item frontend/.env.example frontend/.env.local }
Test-NetConnection project-db-campus.smhrd.com -Port 3310
```

## 2. 실행 순서

| 순서 | 위치 | 명령·확인 |
| --- | --- | --- |
| 1 | Pi 터미널 | Pi 카메라/모터 서버를 하드웨어 담당자가 실행. 카메라 `:8000`, 모터 `:8765` 접근 확인 |
| 2 | PC 백엔드 터미널 | `cd backend` → `powershell -ExecutionPolicy Bypass -File .\run-local.ps1` |
| 3 | PC 프론트 터미널 | `cd frontend` → `npm ci`(최초/변경 시) → `npm run dev` |
| 4 | PC 하드웨어 터미널 | 아래 환경변수 후 **`care_runtime.py` 한 개만** 실행 |

```powershell
cd hardware
$env:ROBOT_DEVICE_ID = 'robot-001'
$env:ROBOT_HTTP_URL = 'http://localhost:8080'
$env:ROBOT_WS_URL = 'ws://localhost:8080/ws/devices'
$credential = Get-Credential -UserName robot -Message 'backend/.env와 같은 기기 토큰 입력'
$env:ROBOT_DEVICE_TOKEN = $credential.GetNetworkCredential().Password
Test-NetConnection 172.30.1.10 -Port 8000
Test-NetConnection 172.30.1.10 -Port 8765
.\.venv\Scripts\python.exe care_runtime.py --host 172.30.1.10
```

IP는 실제 Pi 주소로 바꾼다. 토큰은 환경변수를 설정한 터미널에서만 사용한다. `backend/.env`를 하드웨어가 자동으로 읽지는 않는다. `.venv`가 없다면 [하드웨어 README](../hardware/README.md)의 설치 단계를 먼저 수행한다. **같은 기기에 기존 PC 런타임·`pc_dashboard.py`·시뮬레이터를 동시에 붙이지 않는다.** 백엔드도 한 인스턴스만 실행한다.

## 3. 안전한 연결 확인

전원 ON 전에 별도 터미널에서 읽기 전용 조회를 한다.

```powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/robot-001/status' | ConvertTo-Json -Depth 5
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/robot-001/robot-state' | ConvertTo-Json -Depth 5
```

| 확인 | 기대 결과 |
| --- | --- |
| 백엔드 단독 | 기기 조회 가능, 로봇이 연결되지 않았다면 ONLINE 아님 |
| 통합 런타임 접속 | 유효한 상태가 들어와 ONLINE, `robot-state.stale=false` |
| 제어 준비 | 최신 이동 상태가 `UNKNOWN`이 아니고 `commandsAvailable=true` |
| 모델·사진 | 감지 후 `GET /api/v1/devices/robot-001/detections`에서 최근 이벤트 조회 |

연결·전원 상태를 확인하기 전에는 주행을 시작하지 않는다. 실물 시험은 [통합 테스트 시나리오](통합_테스트_시나리오.md) 순서로 진행한다. 백엔드 재시작 시 Flyway V12·V13이 공유 DB에 적용되므로 로그의 마이그레이션 성공/실패를 확인한다. 다른 브라우저의 선택 아이는 `sessionStorage`에서 자동 공유되지 않으며, 프로필을 열면 기기의 활성 아이가 바뀐다.

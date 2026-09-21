# 한 PC 통합 실행 가이드

개발·시연용 구성이다. React·Spring Boot·`pc_dashboard.py`는 같은 Windows PC에서 실행하고, 카메라/Pi 서버는 로봇 장치에서 실행한다.

```text
React :5173 ──HTTP──> Spring Boot :8080 ──> 공용 PostgreSQL :3310
                              ↑
               WebSocket 상태/명령 · HTTP 탐지 이미지
                              ↑
                  pc_dashboard.py :8081 ──> Pi 카메라 :8000 / 모터
```

## 1. 코드와 별도 전달 항목

기존 저장소에서는 `git fetch origin`, `git switch v2`, `git pull --ff-only origin v2`를 차례대로 실행한다. 처음 받는 PC는 `git clone https://github.com/suim3690-hash/deni.git` 후 `cd deni`, `git switch v2`를 실행한다. 작업 중인 변경이 있으면 강제 초기화하지 않는다.

| Git으로 받는 것 | 별도로 전달할 것 |
| --- | --- |
| 프론트·백엔드·하드웨어 코드, DB 주소·사용자명, 설정 예시 | DB 비밀번호, 기기 토큰, 카메라 비밀번호, 모터 토큰(사용 시), 모델 가중치 |

`backend/.env`, `frontend/.env.local`, `hardware/weights/`는 Git에서 제외된다. 실제 비밀값이나 전체 `.env` 파일을 커밋하지 않는다. 백엔드만 DB에 직접 접속하고, 하드웨어 프로그램은 백엔드의 HTTP/WebSocket을 사용한다.

## 2. 새 PC의 로컬 설정

없는 경우에만 `backend/.env.example`을 `backend/.env`로, `frontend/.env.example`을 `frontend/.env.local`로 복사한다. 기존 로컬 설정 파일은 덮어쓰지 않는다.

| 위치 | 설정 | 설명 |
| --- | --- | --- |
| `backend/.env` | `DB_PASSWORD` | 공용 PostgreSQL 비밀번호. 백엔드에만 필요 |
| `backend/.env` | `ROBOT_DEVICE_ID=robot-001` | DB에 등록된 기기 ID와 동일하게 설정 |
| `backend/.env` | `ROBOT_DEVICE_TOKEN` | 32자 이상 무작위 토큰. 하드웨어 실행 환경과 동일하게 설정 |
| `backend/.env` | `DEVICE_STATUS_MAX_AGE_SECONDS=10` | 최근 상태 보고 기준 |
| `backend/.env` | `ROBOT_CONTROL_STATUS_MAX_AGE_SECONDS=10` | 제어 가능 상태 기준 |
| `backend/.env` | `SERVER_ADDRESS=127.0.0.1` | 세 프로그램이 한 PC일 때 로컬 접속만 허용 |
| `frontend/.env.local` | `VITE_API_BASE_URL=http://localhost:8080` | 실제 API 모드. 변경 후 Vite 재시작 |
| `hardware/weights/` | `object.pt`, `hazard.pt` | 실제 객체 탐지에 필요. Git에는 없음 |

Java 21, Node.js/npm, Python과 Pi 카메라 서버가 필요하다. 현재 DB 주소·사용자명은 `backend/src/main/resources/application.properties`에 있으며 기존 아이·기기 데이터는 공용 DB에 남아 있다. `Test-NetConnection project-db-campus.smhrd.com -Port 3310`으로 새 PC의 네트워크 접근을 확인한다. **이전 PC의 백엔드는 끄고 새 PC에서 하나만 실행**한다. 두 인스턴스는 WebSocket 연결을 공유하지 않지만 같은 DB 명령 대기열을 처리한다.

## 3. 터미널별 실행 순서

| 순서 | 터미널 | 명령 |
| --- | --- | --- |
| 1 | 백엔드 | `cd backend` → `powershell -ExecutionPolicy Bypass -File .\run-local.ps1` |
| 2 | 프론트 | `cd frontend` → `npm ci`(최초 1회) → `npm run dev` |
| 3 | 로봇/Pi | 카메라 서버 실행. 모터 시험 시 주행 서버·Arduino도 준비 |
| 4 | 하드웨어 | 아래 환경변수 설정 후 `pc_dashboard.py` 실행 |

하드웨어 터미널에서는 저장소 **루트**에서 다음을 실행한다. `pc_dashboard.py`는 `backend/.env`를 자동으로 읽지 않으므로 같은 기기 토큰을 이 터미널에 입력한다.

```powershell
python -m pip install -r hardware/requirements.txt
$env:ROBOT_DEVICE_ID = 'robot-001'
$env:ROBOT_HTTP_URL = 'http://localhost:8080'
$env:ROBOT_WS_URL = 'ws://localhost:8080/ws/devices'
$robotCredential = Get-Credential -UserName robot -Message 'backend/.env와 같은 ROBOT_DEVICE_TOKEN 입력'
$env:ROBOT_DEVICE_TOKEN = $robotCredential.GetNetworkCredential().Password

# Pi 주소와 비밀번호는 실제 값으로 교체한다. 카메라·Socket 통신부터 확인한다.
python hardware/pc_dashboard.py --host 'Pi_카메라_IP' --camera-password '카메라_비밀번호' --port 8081 --no-detection
```

백엔드는 8080, 하드웨어 **로컬 대시보드**는 8081이므로 충돌하지 않는다. 카메라 연결 확인 뒤 프로그램을 종료하고, 별도로 받은 가중치와 `hardware/requirements-detection.txt` 의존성을 준비해 `--no-detection` 없이 다시 실행한다. 실제 모터는 하드웨어 담당자가 안전을 확인한 뒤 `--motor --token 'Pi_주행_토큰'`을 추가한다. 같은 기기 ID로 `bridge.py state`나 `simulator.py`를 동시에 실행하지 않는다.

## 4. 연결 확인과 제한

백엔드 실행 후 새 터미널에서 조회한다.

```powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/robot-001/status' | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/robot-001/robot-state' | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/robot-001/detections' | ConvertTo-Json -Depth 5
```

| 단계 | 기대 결과 |
| --- | --- |
| 백엔드만 실행 | 기기 정보 조회 성공, `connectionState=UNKNOWN` |
| 카메라 전용 하드웨어 연결 | `connectionState=ONLINE`, 로봇 상태 `stale=false`, `commandsAvailable=false` |
| 모델 탐지 업로드 | 탐지 목록에 새 `eventId`·이미지 URL. 지원 라벨이면 위험 목록에도 반영 |
| 모터 연결·최근 상태 보고 | `movementState`가 `UNKNOWN`이 아니면 `commandsAvailable=true` 가능 |

새 브라우저에는 기존 PC의 선택 아이가 자동 복원되지 않는다. 프론트는 아이 선택을 브라우저 `sessionStorage`의 `deni:registered-child:api:v1`에만 기억하고, 기존 아이를 조회해 선택하는 로그인 화면은 아직 없다. 같은 아이의 대시보드를 시연하려면 해당 선택 데이터를 **개인 정보로 취급해 별도로 이전**하거나 아이 선택 기능을 구현해야 한다. 무심코 새 아이를 등록하면 다른 `childId`가 생겨 기존 `robot-001`이 보이지 않는다.

실제 API 모드의 홈 전원 버튼은 아직 로봇 모터 명령이 아니다. PAUSE 전달·결과 API는 준비됐지만 프론트 버튼 연결과 실제 모터 시험은 별도로 확인한다.

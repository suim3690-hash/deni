# 하드웨어·Vision AI

Python PC 런타임(`care_runtime.py`)이 Pi 카메라·모터 서버에 연결해 객체를 탐지하고 로봇을 제어한다. 백엔드와는 **WebSocket으로 명령·상태·결과**, **HTTP로 바운딩 박스 사진·탐지 이벤트**를 교환한다. PostgreSQL에는 직접 쓰지 않는다.

## 준비·실행

| 준비물 | 위치·조건 |
| --- | --- |
| Python | 팀에서 검증한 3.13 환경, `requirements.txt`와 `requirements-detection.txt` 설치 |
| 모델 가중치 | Git에 없는 `hardware/weights/object.pt`, `hardware/weights/hazard.pt` 별도 전달 |
| 기기 인증 | `ROBOT_DEVICE_ID`, `ROBOT_DEVICE_TOKEN`을 백엔드 설정과 일치시킴 |
| 접속 주소 | `ROBOT_HTTP_URL`, `ROBOT_WS_URL`, Pi 카메라/모터 서버 IP |

```powershell
cd hardware
py -3.13 -m venv .venv                # 최초 1회
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-detection.txt
$env:ROBOT_DEVICE_ID = 'robot-001'
$env:ROBOT_HTTP_URL = 'http://localhost:8080'
$env:ROBOT_WS_URL = 'ws://localhost:8080/ws/devices'
$credential = Get-Credential -UserName robot -Message '백엔드와 동일한 기기 토큰 입력'
$env:ROBOT_DEVICE_TOKEN = $credential.GetNetworkCredential().Password
.\.venv\Scripts\python.exe care_runtime.py --host 172.30.1.10
```

Pi 서버, 백엔드를 먼저 실행한다. 시작 시 전원은 OFF이며 프론트에서 켠다. **같은 로봇에 `care_runtime.py`, `pc_dashboard.py`, `simulator.py`를 동시에 연결하지 않는다.** 카메라·모터 없이 시험하려면 `check_without_frontend.py`를 사용한다. 상세 절차는 [CARE_RUNTIME_KO.md](CARE_RUNTIME_KO.md)와 [한 PC 통합 실행](../docs/한_PC_통합_실행_가이드.md)을 따른다.

## 실제 처리

| 입력·명령 | PC 런타임의 동작 |
| --- | --- |
| `POWER_ON` / `POWER_OFF` | 주행·탐지 시작 / 모터·탐지 정지. OFF여도 백엔드 통신은 유지 |
| 삼킴 위험 감지 | 동전·구슬·배터리·주사위 라벨에 정지. 사용자 선택 전 자동 재개 안 함 |
| `RECHECK_HAZARD` | 직접 제거 후 대상 라벨이 새 유효 영상에서 2초 연속 보이지 않아야 성공 |
| `RELOCATE` | 같은 라벨 대상 1개를 팔에 확보한 뒤 마커를 따라 이송. 후진 후 물체·마커 근접을 재확인해야 성공 |
| 생활공간 위험 감지 | 전선·콘센트 알림 이벤트 업로드. 로봇은 이 위험만으로 정지하지 않으며, 사용자 확인 완료 후 백엔드가 `RESOLVED`로 처리 |

한 프레임에 여러 물체가 있으면 로컬 이력에는 전체 바운딩박스 사진을 남기고, 백엔드에는 각 물체 바운딩박스에 여백을 둔 **물체별 크롭 사진**을 고유 `eventId`로 업로드한다. 모델의 숫자형 `RISK_LEVELS`는 탐지 우선순위이지 아이 성장단계별 위험도가 아니다. 하드웨어는 라벨·모델 종류·이미지를 보내고 백엔드가 삼킴 위험도를 영아기 `HIGH`, 걸음마 `VERY_HIGH`, 유아 활동기 `MEDIUM`으로 정한다. 이 변경 때문에 모델 점수나 모터 정지 기준을 바꾸지 않는다.

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -q
.\.venv\Scripts\python.exe check_without_frontend.py
```

이송 중 물체가 카메라 아래로 사라져도 확보 단계가 끝났다면 마커만 따라간다. 마커가 사라지면 전진하지 않고 우회전 탐색하며, 12초 안에 찾지 못하거나 후진 뒤 10초 안에 안전 구역 배치를 확인하지 못하면 실패한다. 실물에서는 주사위 가중치, 확보 시간, 마커 가림, 후진/회전 시간, 다중 동일 라벨 물체를 재검증해야 한다. 모델 분석 세부는 [README_DETECTION_KO.md](README_DETECTION_KO.md), 메시지 형식은 [Socket 명세](../docs/Socket_명세서_백엔드-하드웨어.md)에 있다. 비밀번호·토큰·가중치는 Git에 올리지 않는다.

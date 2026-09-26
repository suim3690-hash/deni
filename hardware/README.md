# hardware — 탐지·로봇 제어

Python PC 런타임이 Pi 카메라·모터 서버에 연결해 객체를 탐지하고 로봇을 제어합니다. 백엔드와 WebSocket으로 명령·상태를, HTTP로 탐지 사진을 주고받습니다.

## 파일·폴더 안내

| 경로 | 역할 |
| --- | --- |
| `care_runtime.py` | 실제 로봇 통합 실행 시작점 |
| `care_controller.py`, `care_config.json` | 주행·정지·제거 재확인·이송 상태와 설정 |
| `detection/` | 모델 로딩, 영상 추론, 위험 판정, 마커 인식, 탐지 이력 |
| `bridge.py`, `uploader.py` | 백엔드 WebSocket 연결과 HTTP 탐지 업로드 |
| `pc_client.py`, `protocol.py`, `motor_output.py` | Pi 통신과 모터 명령 출력 |
| `pc_dashboard.py`, `dashboard.html` | 수동 제어·점검용 대시보드 |
| `simulator.py`, `mock_backend.py` | 로봇·백엔드 모의 실행 |
| `check_without_frontend.py` | 실제 장치·DB 없이 명령 흐름 점검 |
| `tests/`, `test_bridge.py` | 탐지·제어·통신 테스트 |
| `aruco_tool.py` | 이송용 ArUco 마커 도구 |
| `setup_detection.py`, `requirements*.txt` | 탐지 환경 준비와 Python 의존성 |
| `start-care.ps1` | Windows 계정에 저장한 기기 설정으로 실행 |
| `examples/` | 업로드·DB 입력 참고 예제 |
| `weights/`, `data/`, `.runtime/` | 로컬 모델·탐지 데이터·실행 데이터 (Git 제외) |

## 실행

Python 3.13, Pi 서버, 실행 중인 백엔드가 필요합니다. 모델 파일 `weights/object.pt`, `weights/hazard.pt`는 별도로 준비합니다.

저장소 루트에서 아래 명령을 실행합니다. `--host`는 실제 Pi IP로 바꿉니다.

```powershell
cd hardware
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-detection.txt
$env:ROBOT_DEVICE_ID = 'robot-001'
$env:ROBOT_HTTP_URL = 'http://localhost:8080'
$env:ROBOT_WS_URL = 'ws://localhost:8080/ws/devices'
$credential = Get-Credential -UserName robot -Message '백엔드와 동일한 기기 토큰 입력'
$env:ROBOT_DEVICE_TOKEN = $credential.GetNetworkCredential().Password
.\.venv\Scripts\python.exe care_runtime.py --host 172.30.1.10
```

시작 시 전원은 OFF이며 프론트에서 켭니다. 같은 로봇에 `care_runtime.py`, `pc_dashboard.py`, `simulator.py`를 동시에 연결하지 않습니다. DB 저장은 백엔드 API를 통합니다.

## 검증·상세 문서

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -q
.\.venv\Scripts\python.exe check_without_frontend.py
```

[장치 연결·제어 동작](CARE_RUNTIME_KO.md) · [모델·탐지 설정](README_DETECTION_KO.md) · [연결 점검](TEST_CONNECTION_KO.md) · [지원 범위](../docs/하드웨어_지원기능.md)

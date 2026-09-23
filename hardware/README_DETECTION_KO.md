# PC 객체 인식 통합판 — COCO 제외 / 모터 세션 진단

> 이전 PC 대시보드 통합 기록이다. 현재 전원·탐지·위험물 처리 실행은 [하드웨어 README](README.md)의 `care_runtime.py`와 [통합 실행 안내](CARE_RUNTIME_KO.md)를 따른다. 아래 ZIP 덮어쓰기·`pc_dashboard.py` 지침을 현재 런타임 설치 절차로 사용하지 않는다.

기존 PC 대시보드에 선택형 객체 인식과 ByteTrack 추적을 연결한 버전입니다. 사용자 PC에 자동 적용되지는 않습니다. Raspberry Pi와 Arduino 파일은 수정하지 않습니다.

## 이번 변경

- COCO 다운로드·모델 로딩·추론을 모두 제외했습니다. 기존 weights/yolo26n.pt가 남아 있어도 사용하지 않습니다.
- Object 모드는 object.pt(배터리·동전·구슬)만, Hazard 모드는 hazard.pt(콘센트·전선)만 실행합니다.
- 이 두 모델에 person/knife/scissors 클래스가 없으므로 사람·칼·가위 검출 및 사람 동시 검출 위험 알림은 동작하지 않습니다. 이후 별도 학습 모델로 확장할 수 있습니다.
- 모터 ACK 대기 후 0.12초를 추가로 쉬던 구조를, ACK 대기 시간을 포함한 0.12초 주기로 바꿨습니다. ACK가 0.12초보다 늦으면 응답 직후 최신 제어 상태를 다시 확인합니다. 명령을 겹쳐 전송하지 않습니다.
- 화면에 Motor TCP, ACK round trip, 최근 제어 해제 사유를 추가했습니다. 최근 사유는 재활성화 후에도 기록으로 남습니다.
- 입력 0.45초/영상 0.7초 안전 타임아웃 및 방향 반전(F↔B, L↔R, S 유지)은 그대로입니다.

## 적용

1. PC 대시보드를 Ctrl+C로 종료합니다.
2. 현재 폴더를 백업한 후 ZIP 안 robot_drive_dashboard 폴더의 내용을 기존 폴더에 덮어씁니다. 폴더를 중첩하지 마세요.
3. 기존 .venv, data, weights 파일은 유지합니다. 패키지는 이전 통합판과 동일하므로 이미 설치했다면 재설치하지 않아도 됩니다.
4. 기존 PC 실행 명령으로 재실행하고 브라우저를 새로고침합니다.

현재 작업 폴더:

```text
C:\Users\Admin\OneDrive\잡것들\DX_Project\3_DX\Raspberry_Pi\robot_drive_dashboard
```

최초 설치에만 필요한 명령(Python 3.12 기준):

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-detection.txt
.\.venv\Scripts\python.exe setup_detection.py
```

setup_detection.py는 로컬 object.pt와 hazard.pt만 검사하고 빈 이미지로 CPU 시험 추론을 합니다. 실제 위험 물체 탐지 성능을 검증하는 것은 아닙니다. 파일이 없으면 다운로드 대신 오류를 표시합니다.

PC 실행(자리표시자를 기존 실제 값으로 변경):

```powershell
.\.venv\Scripts\python.exe pc_dashboard.py --host 172.30.1.10
```

Pi 실행:

```bash
/usr/bin/python3 /home/capstone-6/DX_Project/start_robot.py
```

대시보드: http://127.0.0.1:8080

`--detection-mode hazard`로 초기 모드를 정할 수 있고, `--no-detection`은 영상과 주행만 실행합니다. 두 터미널은 실행 상태로 유지합니다.

## 모터 세션 해제 원인 구분

| 화면/메시지 | 의미 |
|---|---|
| Window focus lost / Tab hidden | 다른 창·탭으로 전환하여 제어가 해제됨 |
| Browser heartbeat expired | PC 서버에 브라우저 입력 신호가 0.45초 넘게 도착하지 않음 |
| Browser video frame expired | 브라우저가 확인한 영상 프레임이 0.7초보다 오래됨 |
| Camera receipt expired / Camera stream error | Pi 영상 수신 중단 또는 오류 |
| Control stopped / Dashboard connection lost | 브라우저↔PC 요청 오류·시간 초과 |
| Motor connection failed: TimeoutError 등 | PC↔Pi TCP 통신/ACK 처리 실패. 모터 통신 스레드 종료 |

브라우저 측 영상 신선도 기준은 650ms이며, 제어 HTTP 요청은 350ms 타임아웃입니다. 따라서 PC의 0.45초/0.7초 조건보다 먼저 해제될 수도 있습니다. 키를 계속 누르고 있어도 브라우저 신호나 영상이 오래되면 해제됩니다.

Motor TCP가 CONNECTED이면 제어 권한만 해제된 상태일 수 있습니다. 영상·창 포커스가 회복되면 Enable controls를 다시 누릅니다. DISCONNECTED이고 Motor connection failed가 나오면 연결 원인을 해결한 후 PC 대시보드를 재시작해야 합니다. 자동 재연결·자동 주행 재개는 구현하지 않았습니다.

COCO 제거로 추론량은 줄지만, 현재 기기에서 끊김 원인을 측정한 것은 아닙니다. 최근 제어 해제 사유와 ACK round trip을 확인해야 특정 원인을 판단할 수 있습니다. 안전 타임아웃을 단순히 늘려 해결하지 않았습니다.

## 인식·추적·알림

- 선택한 커스텀 모델 한 개에 ByteTrack을 적용하며, 모델명·추적 ID를 화면과 스냅샷에서 확인할 수 있습니다.
- 최신 JPEG 한 장을 공유 메모리로 전달하고 별도 프로세스가 디코딩·흐림 검사·추론·저장을 수행합니다. 원본 영상 수신과 모터 제어는 추론을 기다리지 않습니다.
- 모드 전환 시 모델·추적·분류 투표·쿨다운 상태를 새로 시작합니다. 이전 모드의 늦은 결과는 현재 화면에 반영하지 않습니다.
- 주의: coin, marble, socket, wire. 경고: battery. 클래스명 battery만으로 버튼전지 여부를 따로 구분하지 않습니다. socket/wire만으로 통전·실제 위험 상태를 판정하지 않습니다.
- 신뢰도 0.35 이상 위험 물체를 표시하고 10회 관측 중 8회가 같은 클래스이면 일반 발견 이력을 기록합니다. 기록 시점은 최초 표시보다 늦을 수 있습니다.
- 객체별 10초 쿨다운을 적용하며, 위험도 상승은 쿨다운을 우회합니다. 추적 ID는 영구 물체 식별자가 아니므로 가림·로봇 이동·재진입 시 새 ID가 될 수 있습니다.
- 동일 분석 프레임을 JPEG로 저장하고 data/events.sqlite3와 data/snapshots에서 관리합니다. 위치 좌표는 없습니다.
- 원본 라이브 영상은 유지하며 박스는 발견 사진에 표시합니다. 오래된 AI 결과나 흐림 건너뜀을 안전으로 표시하지 않습니다.

## 설정과 파일

`detection/config.py`에서 조정합니다.

| 항목 | 초기값 |
|---|---:|
| BLUR_THRESHOLD | 60 (0이면 흐림 제외 끔) |
| DETECT_CONF / ALERT_CONF | 0.10 / 0.35 |
| STABLE_WINDOW / STABLE_VOTES | 10 / 8 |
| ALERT_COOLDOWN_SEC | 10초 |
| RESULT_TTL | 3초 (제어 타임아웃과 별개) |
| CPU_THREADS | 2 |
| MAX_INFERENCE_FPS | 5 (상한이며 속도 보장 아님) |
| TRACK_FORGET_SEC | 15초 |
| MAX_EVENTS | 500 (초과한 오래된 기록·사진 삭제) |

`model_loader.py`: 모델 로딩과 ByteTrack, `service.py`: 최신 프레임/프로세스 관리, `worker.py`: 추론 실행, `stabilization.py`: 클래스 투표, `risk_engine.py`: 위험도·중복 억제, `event_store.py`: 사진·이력, `http_api.py`/`ui.js`: 모드 선택·상태·사진 UI.

패키지 버전은 제공된 학습 코드에 맞춘 ultralytics==8.4.146, opencv-python>=4.10,<6, lap>=0.5.12,<1입니다.

## 검증 범위

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

자동 테스트 14개: 최신 프레임 처리, 기존 타임아웃 정지, 클래스 안정화·쿨다운, 모터 방향 반전, 모드 인증·늦은 결과 폐기, 사진·HTTP 이력, ACK 추가 대기 제거, 해제 사유 보존, COCO 없는 로컬 모델 설치 검사.

개발 환경에서는 실제 Windows·Pi·모터·모델 추론을 검증하지 못했습니다. 이전 setup_detection.py가 사용자 PC에서 성공한 것은 확인했으나, 이번 변경의 실제 주행 안정성·추론 성능은 해당 기기에서 확인해야 합니다.

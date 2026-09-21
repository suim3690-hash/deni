# 카메라 객체 인식 → 백엔드 수신 확인

영상 방향: PC 수신 시 기본 180도 회전한다. 같은 회전 프레임을 화면·추론·탐지 이미지 업로드에 사용한다.
Pi 원본 스트림 자체는 변경하지 않았다. Pi에서 이미 회전한 영상을 보내게 되면 `--rotation 0`으로 중복 회전을 끈다.

이번 확인의 끝은 HTTP 탐지 수신 및 WebSocket 상태 수신이다. 프론트 알림 표시·DB 직접 저장은 범위에 없다.
실제 백엔드가 없는 지금은 로컬 모의 수신기로 확인한다. 모의 서버 통과가 실제 Spring 서버 통과를 뜻하지는 않는다.

## 1. 코드와 모델 준비

현재 PC에는 `hardware/.venv`를 생성하고 추론 패키지를 설치했다. 이후 아래 문서의 `python` 대신
`& .\.venv\Scripts\python.exe`를 사용하면 된다. 기존 Anaconda의 시스템 패키지를 참조하되
추가 패키지는 이 가상환경에 설치했다. 두 가중치의 클래스 및 CPU 추론 확인은 통과했다.
실제 Pi 영상에서도 status=ok, stale=false, processed 증가를 확인했다.
이 결과는 카메라 추론 실행 확인이며, 대상 물체 감지·HTTP 이벤트 수신은 별도로 로그를 확인한다.

`Raspberry_Pi/robot_drive_dashboard` 기본판을 이 폴더로 복사했다. 원본은 유지했다.
`detection/`, `pc_dashboard.py`, `dashboard.html`, `pc_client.py`, `protocol.py`, 테스트 및 설정을 포함한다.
`weights/object.pt`, `weights/hazard.pt`도 로컬에 복사했으며 Git에서는 제외한다.
다른 PC에 전달할 때 두 가중치는 별도로 같은 위치에 배치한다. COCO판과 ArUco는 이번 통합에 포함하지 않았다.

Anaconda의 기존 객체 인식 환경에서 실행한다. 아래 모든 PC 명령은 이 폴더 기준이다.

```powershell
cd "C:\Users\Admin\OneDrive\잡것들\DX_Project\3_DX\deni\hardware"
python -m pip install -r requirements.txt -r requirements-detection.txt
python setup_detection.py
```

Python 명령을 찾지 못하지만 현재 PC의 Anaconda 기본 환경을 사용하려면
`python` 대신 `& C:\Users\Admin\anaconda3\python.exe`를 쓴다.
Anaconda 기본 환경에는 OpenCV/Ultralytics/lap이 없어 전용 `.venv`에 설치했다.
setup은 로컬 두 모델의 클래스와 CPU 추론을 검사한다. 실제 촬영 영상 정확도를 검사하는 것은 아니다.

## 2. Pi 카메라 유지

Pi에서 기존 `pi_video_server.py`를 실행한 상태를 유지한다. IP는 `192.168.219.145`이다.
PC 브라우저에서 `http://192.168.219.145:8000/`을 열어 영상 확인:
사용자 `robot`, 암호는 Pi 터미널의 Browser password.
Arduino 및 주행 수신기, 모터 TOKEN은 필요 없다.

## 3. 터미널 A — 로컬 수신 서버

아래 키는 로컬 테스트 전용 공개 문자열이다. 실제 서버에서는 별도 키를 사용한다.

```powershell
$env:ROBOT_DEVICE_ID = 'robot-test'
$env:ROBOT_DEVICE_TOKEN = 'local-test-token-0123456789abcdef'
python mock_backend.py
```

127.0.0.1의 HTTP 18080, WebSocket 18081을 사용한다. 실행 후 켜둔다.
모의 서버는 메모리에 이벤트 ID/지문만 보관한다. 파일이나 공유 DB에 이미지를 저장하지 않는다.
프로세스 재시작 시 모의 서버 중복 기록은 초기화된다.

## 4. 터미널 B — 실제 카메라 객체 인식 및 자동 HTTP 전송

새 터미널에서도 같은 hardware 폴더와 Python 환경을 사용한다.

```powershell
$env:ROBOT_DEVICE_ID = 'robot-test'
$env:ROBOT_DEVICE_TOKEN = 'local-test-token-0123456789abcdef'
$env:ROBOT_HTTP_URL = 'http://127.0.0.1:18080'
python pc_dashboard.py --host 192.168.219.145 --camera-password "Pi의_Browser_password" --port 8081 --detection-mode object
```

`http://127.0.0.1:8081/`에서 모델 로딩 및 영상을 확인하고 동전·구슬·배터리 등 해당 모델 대상을
밝고 선명하게 몇 초 동안 보여준다. 흐림 제거·반복 감지 확인을 통과한 이벤트만 전송한다.
위험 모델은 화면에서 선택하거나 실행 인자를 `--detection-mode hazard`로 바꾼다.
두 커스텀 모델은 동시에 실행하지 않고 선택한 하나만 사용한다.

성공 기준:

```text
터미널 A: HTTP RECEIVED eventId=... model=OBJECT label=동전 bytes=...
터미널 B: INFO Event ...: sent (HTTP 200)
```

탐지 이미지가 백엔드 형식으로 도착한 것을 뜻한다. 모의 서버의 hazardId는 항상 null이다.
단순 화면 바운딩 박스 표시만으로 HTTP 이벤트가 생성된 것으로 판단하지 않는다.
전송되지 않으면 모델 오류, 흐린 영상, 이벤트 생성 여부, HTTP 환경변수, storage_error를 확인한다.
송신 기록은 `python bridge.py status`로 확인한다.

기본은 카메라 전용이다. 내일 실제 모터를 연결할 때만 `--motor --token "Pi_주행_TOKEN"`을 추가한다.
백엔드 PAUSE와 기존 모터 제어는 아직 서로 연결하지 않았으며 성공으로 보고하지 않는다.

## 5. WebSocket 상태 송신과 명령 수신

`pc_dashboard.py`가 4절의 환경변수로 실행되면 WebSocket 세션이 자동으로 함께 시작된다.
별도 터미널이나 `python bridge.py state`는 더 이상 필요하지 않다.
`ROBOT_WS_URL`을 설정하지 않으면 기본값 `ws://localhost:8080/ws/devices`를 사용하므로,
모의 서버로 시험할 때는 `$env:ROBOT_WS_URL = 'ws://127.0.0.1:18081/ws/devices'`를 함께 설정한다.

터미널 A에 아래가 나오면 상태 송신 성공이다. 상태가 바뀔 때와 약 1초 주기로 전송한다.

```
WS RECEIVED device=robot-test operation=UNKNOWN movement=UNKNOWN
```

`movement`는 모터가 실제로 응답한 명령에서 나온다. Arduino가 없으면 계속 `UNKNOWN`이다.
`operation`은 백엔드 PAUSE를 모터가 확인해 준 뒤에만 `PAUSED`가 된다.
배터리·거리·시간은 측정하지 않으므로 항상 null이며, 추정값으로 채우지 않는다.

### 프론트엔드 제어 명령 수신 시험

실제 경로는 프론트엔드 → 백엔드 → WebSocket → 이 프로그램이다.
모의 서버에서는 아래로 백엔드의 PAUSE 발행을 대신한다. 만료는 실제와 같은 10초다.

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:18080/mock/command `
  -ContentType 'application/json' -Body '{"command":"PAUSE"}'
```

터미널 A에서 다음 순서를 확인한다.

```
WS COMMAND SENT      commandId=... command=PAUSE
WS COMMAND_ACK       commandId=... status=DELIVERED
WS COMMAND_RESULT    commandId=... status=... operation=... error=...
```

`COMMAND_RESULT`는 하드웨어 상태에 따라 달라진다.

| 상황 | status | errorCode |
|---|---|---|
| Arduino 없음 (현재 기본) | FAILED | HARDWARE_NOT_CONNECTED |
| 모터가 정지를 확인 | SUCCEEDED | (없음, operation=PAUSED) |
| 3초 안에 정지 미확인 | FAILED | STOP_NOT_CONFIRMED |
| PAUSE 외 명령 | FAILED | UNSUPPORTED_COMMAND |

백엔드가 내려보내는 명령은 PAUSE 하나다. RESUME은 백엔드가 접수 단계에서 막으므로 기기까지 오지 않는다.
백엔드는 `operationState=PAUSED`가 아닌 `SUCCEEDED`를 거부하므로, 정지를 확인하기 전에는 성공으로 보고하지 않는다.
같은 commandId가 다시 오면 저장된 결과를 그대로 재전송하며 명령을 두 번 실행하지 않는다.

## 6. 카메라 없이 HTTP만 검사

터미널 B의 환경변수를 설정한 뒤, 실제 JPEG/PNG 파일로:

```powershell
python bridge.py enqueue --image "검사용_이미지.jpg" --label 동전 --model OBJECT
python bridge.py flush
python bridge.py status
```

enqueue는 원본을 로컬에 보관한다. flush는 pending 항목을 전송한다. 서버가 없으면 pending을 유지한다.
이 검사는 모델이 해당 물체를 인식했다는 증거가 아니라 HTTP 이미지 전송 검사다.
실제 카메라 전송을 구분하려면 수신 로그의 eventId를 확인한다.

## 7. 실제 백엔드가 준비되었을 때

모의 서버 대신 실제 v2 서버 주소로 ROBOT_HTTP_URL/ROBOT_WS_URL을 바꾸고,
등록된 ROBOT_DEVICE_ID와 실제 ROBOT_DEVICE_TOKEN을 사용한다. 클라이언트 코드는 바꾸지 않는다.
백엔드 실행·기기 등록·DB 설정은 백엔드 담당자 영역이다.

- HTTP: `POST /api/v1/hardware/detections`의 200 응답에서 전송한 eventId가 일치하면 접수 성공.
- WS: 백엔드의 `RECEIPT`/`accepted:true`가 상태 메시지 접수 증거다. 접속 성공만으로 접수 성공을 판단하지 않는다.
- HTTP 401/403은 인증 값, 404는 주소·기기 등록, 409는 같은 이벤트의 내용 충돌 등을 서버 응답으로 확인한다.
- 한글 5종 외 라벨은 실제 서버에서도 hazardId=null일 수 있다. 위험 분류와 통신 수신 성공은 별개다.

## 8. 자동 검사

```powershell
python -m unittest discover -s . -p test_bridge.py -v
python -m unittest discover -s tests -v
```

HTTP 필드·인증·재시도·물체별 이벤트와 한글 매핑·로컬 수신, WebSocket 상태·ACK·실패 결과·중복·만료,
기존 탐지 안정화/저장/대시보드 로직을 검사한다. 실제 신경망 정확도 및 실제 Spring/DB 종단 통합은 별도 확인한다.

현재 제한: 디스크 저장 실패 시 로컬 이벤트와 전송 대기열을 하나의 트랜잭션으로 보장하지 않는다.
백엔드 연결 실패 자체는 원본 대기열을 유지하며 재시도한다. 장기간 이미지 보관 용량 자동 정리는 미구현이다.

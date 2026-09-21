# 하드웨어·모델 코드

**최신 카메라→HTTP 수신 테스트: [TEST_CONNECTION_KO.md](TEST_CONNECTION_KO.md)**

기본 객체 인식판을 이 폴더에 복사했고 `pc_dashboard.py`에서 확정 탐지 이벤트를 HTTP 대기열에 연결했다.
기본은 모터 없는 카메라 모드이며 `ROBOT_HTTP_URL` 설정 시 자동 업로드한다.
로컬 `mock_backend.py`로 실제 서버 없이 HTTP·WebSocket 도착 로그를 확인할 수 있다.

## 실제 연결 모듈 (2026-09-20 추가)

`bridge.py`는 v2의 Java 수신 코드에 맞춘 PC 통신 모듈이다. 하드웨어 코드는 이 폴더에서 관리한다.
백엔드 코드·공유 DB 설정은 변경하지 않는다. 카메라 추론 실행은 `pc_dashboard.py`를 사용하며
탐지 결과를 이 모듈로 전송한다. Arduino 드라이버와 백엔드 PAUSE는 아직 미연결이다.

현재 실행 모드는 모터 미연결이다. 동작·이동 UNKNOWN, 배터리·시간·거리 null을 1초마다 보고한다.
PAUSE를 받으면 COMMAND_ACK 이후 FAILED/HARDWARE_NOT_CONNECTED를 반환한다. 가짜 정지 성공은 보내지 않는다.
백엔드는 유효 상태 보고를 ONLINE으로 저장하고 Socket 연결 시 PAUSE 전달 가능으로 판단하므로,
웹의 ONLINE/commandsAvailable이 실제 모터 장착을 뜻하지 않는 점은 백엔드·프론트 담당자와 공유해야 한다.

### 서버 없이 검증

Windows / Python 3.13 (Anaconda), requests 및 websockets 16.0에서 검증했다.
백엔드·DB·기기 키 없이 로컬 HTTP/WebSocket 서버를 잠시 열어 검사한다. 실제 서버에는 전송하지 않는다.
저장소 루트에서 실행:

```powershell
python -m pip install -r hardware/requirements.txt
python -m unittest discover -s hardware -p test_bridge.py -v
```

검증 범위: multipart 경로·필드·인증, 503 후 원본 재전송, 이벤트 ID 충돌,
실제 WebSocket 접속·상태·ACK·실패 결과, 프로세스 재시작에 해당하는 저장소 재오픈 후 중복 결과 유지,
만료 명령 무시. Spring/DB와의 종단 간 통합 및 실제 모터·카메라 동작 검증은 아니다.

### 백엔드가 준비된 뒤 실행

등록된 ID, 최소 32자 토큰, 접속 주소가 필요하다. 토큰을 소스에 저장하지 않는다.

```powershell
$env:ROBOT_DEVICE_ID = '등록된_ID'
$env:ROBOT_HTTP_URL = 'http://백엔드IP:8080'
$env:ROBOT_WS_URL = 'ws://백엔드IP:8080/ws/devices'
$connectionCredential = Get-Credential -UserName robot -Message '암호 칸에 기기 인증 키 입력'
$env:ROBOT_DEVICE_TOKEN = $connectionCredential.GetNetworkCredential().Password
python hardware/bridge.py state
```

Socket 단절은 1~30초 간격으로 재연결한다. 시뮬레이터와 같은 ID로 동시에 실행하지 않는다.
상태 메시지의 서버 거부는 로그로 표시하며 애플리케이션 RECEIPT의 영속 추적은 아직 구현하지 않았다.
COMMAND_RESULT는 전송 전에 보관하여 같은 commandId를 다시 받으면 같은 completedAt으로 결과만 재전송한다.
서버가 명령을 재전송하지 않는 현재 계약상 연결 단절 중 유실된 결과의 자동 복구는 보장하지 않는다.
실제 모터 구현 시 실행 전 명령 기록, 정지 확인, 유실 결과 재전송·접수 확인까지 추가해야 한다.

탐지 이미지 보관 및 HTTP 전송은 별도 명령이다. WebSocket 상태 실행 중에도 별도 터미널에서 사용할 수 있다.
새 터미널에는 환경변수를 다시 설정한다.

```powershell
python hardware/bridge.py enqueue --image annotated.jpg --label 동전 --model HAZARD
python hardware/bridge.py flush
python hardware/bridge.py status
```

- enqueue는 네트워크 전송 없이 이미지를 보관하고 이벤트 UUID를 출력한다.
- flush는 최대 20건을 전송한다. 연결 오류·408·429·5xx는 pending으로 유지하므로 나중에 flush를 다시 실행한다.
- 같은 이벤트를 다시 enqueue하려면 출력된 UUID를 `--event-id`에 넣고 같은 원본을 사용한다.
- 다른 HTTP 오류나 예상과 다른 응답은 rejected로 보관한다. status로 확인하고 원인을 해결한다.
- 로컬 `.runtime/transport.sqlite3`는 재전송 이미지·명령 결과 보관용 파일이며 공유 DB 업로드가 아니다.
  인증 키는 저장하지 않는다. 이미지 보관 용량 자동 정리는 아직 없으며 sent 데이터도 유지한다.
- state/flush는 각각 한 프로세스만 실행한다. 여러 flush의 병행 실행은 지원하지 않는다.

### 객체 인식 코드에서 호출

인식 코드가 `hardware` 안에 있을 때:

```python
from bridge import Bridge
import os

bridge = Bridge(os.environ['ROBOT_DEVICE_ID'], os.environ['ROBOT_DEVICE_TOKEN'],
                os.environ['ROBOT_HTTP_URL'], os.environ['ROBOT_WS_URL'],
                'hardware/.runtime/transport.sqlite3')
# 프레임마다가 아니라 확정한 탐지 이벤트마다 호출한다.
# annotated_jpeg_bytes는 모델이 바운딩 박스를 그린 실제 JPEG 바이트다.
event_id = bridge.enqueue_detection(annotated_jpeg_bytes, '동전', 'HAZARD')
# HTTP 작업은 최대 수십 초 기다릴 수 있으므로 영상/제어 루프와 분리한다.
bridge.flush_once()
bridge.close()
```

모델의 라벨을 임의로 한글 위험 라벨로 바꾸지 않는다. 실제 모델 라벨 대응표를 확정해야 한다.
v2 업로드는 eventId/modelType/objectLabel/image만 받으며 촬영 시각·신뢰도·ArUco 결과 필드는 아직 계약에 없다.
같은 물체의 연속 프레임을 한 이벤트로 묶는 판단은 인식 프로그램에서 추가해야 한다.

현재 실행 예제와 메시지 계약은 [Socket 명세](../docs/Socket_명세서_백엔드-하드웨어.md)를 따른다.

| 파일 | 용도 |
| --- | --- |
| `simulator.py` | 인증된 Socket 연결·주기 상태·PAUSE ACK/모의 완료 |
| `examples/upload_detection.py` | JPEG/PNG 탐지 프레임 HTTP 업로드 |
| `examples/robot_db_writer.py` | 이전 직접 DB 입력 예제, 신규 Socket과 병행 금지 |
| `requirements.txt` | 시뮬레이터·HTTP 예제 의존성 |

기존 `v2` 브랜치가 있으므로 작업 브랜치는 `hardware`로 만든다 (`v2/hardware`는 Git 이름 충돌).

하드웨어 담당자는 로봇 제어와 객체 탐지 실행 코드를 이 폴더에서 관리한다.

## 코드 전달 기준

코드와 함께 다음 정보를 작성한다.

| 항목 | 작성 내용 |
| --- | --- |
| 실행 환경 | 운영체제, Python 등 언어 버전, 장치·드라이버 |
| 설치 방법 | 의존성 설치 명령 또는 `requirements.txt` |
| 실행 방법 | 진입 파일과 명령, 필요한 환경변수 이름 |
| 기기 식별자 | 백엔드 `devices.id`와 같은 `deviceId` |
| 지원 명령 | 실제 받을 수 있는 명령과 완료 판단 방법 |
| 상태·탐지 출력 | 측정 가능한 값, 단위, 생성 주기 |
| 장애 대응 | 연결 단절, 명령 중복, 로봇 자체 오류 처리 |

DB 비밀번호나 향후 발급할 기기 키는 코드에 넣지 않고 환경변수로 읽는다. 모델 가중치·영상처럼 큰 파일은
Git에 바로 추가하지 말고 저장 위치와 내려받는 방법을 문서화한다.

## 통합 계약

- [Socket 메시지 명세](../docs/Socket_명세서_백엔드-하드웨어.md)
- [하드웨어 지원 기능표](../docs/하드웨어_지원기능.md)
- [통합 테스트 시나리오](../docs/통합_테스트_시나리오.md)

`examples/robot_db_writer.py`는 WebSocket 연결 전 탐지 이벤트와 최신 로봇 상태를 공유 PostgreSQL에
직접 저장하는 개발용 예제다. 최종 통신 경로가 확정되면 백엔드 수신 방식으로 교체할지 결정한다.

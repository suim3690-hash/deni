"""다른 PC의 모델/하드웨어 프로그램에서 import해 사용. 실행만으로 데이터를 만들지 않는다."""
import os
from datetime import datetime
from uuid import UUID

import psycopg


def connect():
    return psycopg.connect(
        host=os.environ.get("DB_HOST", "project-db-campus.smhrd.com"),
        port=int(os.environ.get("DB_PORT", "3310")),
        dbname=os.environ.get("DB_NAME", "campus_lgdx_2"),
        user=os.environ.get("DB_USER", "campus_lgdx_2"),
        password=os.environ["DB_PASSWORD"],
        connect_timeout=10,
        application_name="robot-db-writer",
    )


def save_detection(conn, *, event_id: UUID, device_id: str, model_type: str,
                   label: str, image_bytes: bytes):
    """바운딩 박스 처리가 끝난 JPEG/PNG 바이트를 저장. 재시도는 같은 ID와 바이트를 사용한다."""
    if not 0 < len(image_bytes) <= 5 * 1024 * 1024:
        raise ValueError("이미지는 1바이트~5MiB여야 합니다.")
    if image_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        content_type = "image/png"
    elif image_bytes.startswith(b"\xff\xd8\xff"):
        content_type = "image/jpeg"
    else:
        raise ValueError("JPEG 또는 PNG 이미지가 필요합니다.")
    with conn.transaction():
        with conn.cursor() as cursor:
            cursor.execute("""
                INSERT INTO detection_events
                    (event_id, device_id, model_type, object_label, frame_image, image_content_type)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (event_id) DO NOTHING RETURNING event_id
            """, (event_id, device_id, model_type, label, image_bytes, content_type))
            if cursor.fetchone() is not None:
                return event_id
            cursor.execute("""
                SELECT device_id, model_type, object_label, frame_image, image_content_type
                FROM detection_events WHERE event_id = %s
            """, (event_id,))
            existing = cursor.fetchone()
            if existing is None or tuple(existing) != (device_id, model_type, label, image_bytes, content_type):
                raise ValueError("동일 event_id에 다른 데이터가 존재합니다. 원본 이벤트를 확인하세요.")
    return event_id


def save_live_state(conn, *, device_id: str, operation_state: str, movement_state: str,
                    sampled_at: datetime, movement_duration_ms=None, movement_distance_m=None):
    """sampled_at은 실제 측정한 UTC 시각. 재전송에서 바꾸지 않는다. 반환 False는 중복/과거 보고."""
    if sampled_at.tzinfo is None or sampled_at.utcoffset() is None:
        raise ValueError("sampled_at에는 시간대가 필요합니다.")
    with conn.transaction():
        with conn.cursor() as cursor:
            cursor.execute("""
                INSERT INTO robot_live_state
                    (device_id, operation_state, movement_state, sampled_at,
                     movement_duration_ms, movement_distance_m)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (device_id) DO UPDATE SET
                    operation_state = EXCLUDED.operation_state,
                    movement_state = EXCLUDED.movement_state,
                    sampled_at = EXCLUDED.sampled_at,
                    movement_duration_ms = EXCLUDED.movement_duration_ms,
                    movement_distance_m = EXCLUDED.movement_distance_m
                RETURNING device_id
            """, (device_id, operation_state, movement_state, sampled_at,
                  movement_duration_ms, movement_distance_m))
            return cursor.fetchone() is not None

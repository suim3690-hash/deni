-- 다른 PC의 탐지 원본. 위험도 평가가 완료된 hazards와 구분한다.
CREATE TABLE detection_events (
    event_id UUID PRIMARY KEY,
    device_id VARCHAR(100) NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    model_type VARCHAR(10) NOT NULL CHECK (model_type IN ('HAZARD', 'OBJECT')),
    object_label VARCHAR(100) NOT NULL CHECK (char_length(btrim(object_label)) BETWEEN 1 AND 100),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    frame_image BYTEA NOT NULL CHECK (octet_length(frame_image) BETWEEN 1 AND 5242880),
    image_content_type VARCHAR(20) NOT NULL CHECK (image_content_type IN ('image/jpeg', 'image/png'))
);
CREATE INDEX idx_detection_events_device_time ON detection_events(device_id, detected_at DESC, event_id);

-- 최신 스냅샷 1행/기기. 이동 시간·거리는 현재 이동 구간의 누적값이며 미측정은 NULL.
CREATE TABLE robot_live_state (
    device_id VARCHAR(100) PRIMARY KEY REFERENCES devices(id) ON DELETE RESTRICT,
    operation_state VARCHAR(20) NOT NULL CHECK (operation_state IN ('RUNNING', 'PAUSED', 'RELOCATING', 'UNKNOWN')),
    movement_state VARCHAR(20) NOT NULL CHECK (movement_state IN ('FORWARD', 'TURNING', 'BACKWARD', 'STOPPED', 'UNKNOWN')),
    movement_duration_ms BIGINT CHECK (movement_duration_ms >= 0),
    movement_distance_m NUMERIC(12,3) CHECK (movement_distance_m >= 0 AND movement_distance_m < 1000000000),
    sampled_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION validate_robot_live_state() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sampled_at > clock_timestamp() THEN
        RAISE EXCEPTION 'sampled_at must not be in the future' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.device_id IS DISTINCT FROM OLD.device_id THEN
            RAISE EXCEPTION 'device_id is immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.sampled_at < OLD.sampled_at THEN RETURN NULL; END IF;
        IF NEW.sampled_at = OLD.sampled_at THEN
            IF ROW(NEW.operation_state, NEW.movement_state, NEW.movement_duration_ms, NEW.movement_distance_m)
                IS DISTINCT FROM ROW(OLD.operation_state, OLD.movement_state, OLD.movement_duration_ms, OLD.movement_distance_m) THEN
                RAISE EXCEPTION 'same sampled_at has different state' USING ERRCODE = '23514';
            END IF;
            RETURN NULL;
        END IF;
    END IF;
    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_robot_live_state BEFORE INSERT OR UPDATE ON robot_live_state
    FOR EACH ROW EXECUTE FUNCTION validate_robot_live_state();

-- 최초 DB 수신 시각을 사용한다. 외부 PC가 임의 서버 시각을 입력하지 않는다.
CREATE FUNCTION stamp_detection_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.detected_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_detection_event BEFORE INSERT ON detection_events
    FOR EACH ROW EXECUTE FUNCTION stamp_detection_event();

-- 개발 단계의 1아이 : 1기기 연결. 실제 연결/운행 상태는 보고를 받은 경우에만 저장한다.
CREATE TABLE devices (
    id VARCHAR(100) PRIMARY KEY,
    child_id UUID NOT NULL UNIQUE REFERENCES children(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    connection_state VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    operation_state VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    battery_percent INTEGER,
    last_reported_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_devices_id CHECK (id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$'),
    CONSTRAINT ck_devices_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_devices_connection CHECK (connection_state IN ('ONLINE', 'OFFLINE', 'UNKNOWN')),
    CONSTRAINT ck_devices_operation CHECK (operation_state IN ('RUNNING', 'PAUSED', 'STOPPING', 'RESUMING', 'READY_TO_RESUME', 'UNKNOWN')),
    CONSTRAINT ck_devices_battery CHECK (battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_devices_report CHECK (
        (last_reported_at IS NULL AND last_seen_at IS NULL AND connection_state = 'UNKNOWN'
            AND operation_state = 'UNKNOWN' AND battery_percent IS NULL)
        OR (last_reported_at IS NOT NULL AND last_seen_at IS NOT NULL AND last_reported_at <= last_seen_at)
    )
);

CREATE TABLE hazards (
    id UUID PRIMARY KEY,
    child_id UUID NOT NULL REFERENCES children (id) ON DELETE RESTRICT,
    device_id VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    object_type VARCHAR(50) NOT NULL,
    object_name VARCHAR(100) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    risk_reason TEXT,
    detected_at TIMESTAMPTZ NOT NULL,
    location_label VARCHAR(200),
    map_image_url TEXT,
    marker_x DOUBLE PRECISION,
    marker_y DOUBLE PRECISION,
    capture_image_url TEXT,
    device_operation_state VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_hazards_status
        CHECK (status IN ('ACTIVE', 'RESOLVED')),
    CONSTRAINT ck_hazards_risk_level
        CHECK (risk_level IN ('VERY_HIGH', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_hazards_device_operation_state
        CHECK (device_operation_state IN (
            'RUNNING', 'PAUSED', 'STOPPING', 'RESUMING', 'READY_TO_RESUME', 'UNKNOWN'
        )),
    CONSTRAINT ck_hazards_marker_pair
        CHECK ((marker_x IS NULL AND marker_y IS NULL) OR (marker_x IS NOT NULL AND marker_y IS NOT NULL)),
    CONSTRAINT ck_hazards_marker_x
        CHECK (marker_x IS NULL OR marker_x BETWEEN 0.0 AND 1.0),
    CONSTRAINT ck_hazards_marker_y
        CHECK (marker_y IS NULL OR marker_y BETWEEN 0.0 AND 1.0)
);

CREATE INDEX ix_hazards_device_status_detected_at
    ON hazards (device_id, status, detected_at DESC);

CREATE INDEX ix_hazards_child_detected_at
    ON hazards (child_id, detected_at DESC);

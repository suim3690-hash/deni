-- 요청 접수 기록만 저장한다. 실행 결과·안전 완료를 추측해서 기록하지 않는다.
CREATE TABLE operation_requests (
    id UUID PRIMARY KEY,
    device_id VARCHAR(100) NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    hazard_id UUID REFERENCES hazards(id) ON DELETE RESTRICT,
    idempotency_key UUID NOT NULL,
    kind VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_operation_requests_device_key UNIQUE (device_id, idempotency_key),
    CONSTRAINT ck_operation_requests_scope CHECK (
        (kind = 'PAUSE' AND hazard_id IS NULL AND status = 'REQUESTED')
        OR (kind = 'DIRECT_REMOVAL_CHECK' AND hazard_id IS NOT NULL AND status = 'UNKNOWN')
    )
);
CREATE INDEX idx_operation_requests_device_created ON operation_requests(device_id, created_at DESC);
CREATE INDEX idx_operation_requests_hazard_created ON operation_requests(hazard_id, created_at DESC)
    WHERE hazard_id IS NOT NULL;

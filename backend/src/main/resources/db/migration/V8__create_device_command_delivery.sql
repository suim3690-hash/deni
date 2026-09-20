CREATE TABLE device_command_delivery (
    command_id UUID PRIMARY KEY REFERENCES operation_requests(id),
    device_id VARCHAR(100) NOT NULL REFERENCES devices(id),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','SENT','DELIVERED','SUCCEEDED','FAILED','EXPIRED','UNKNOWN')),
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(100)
);
-- Historical receipts are deliberately not added to this delivery queue.
CREATE INDEX idx_device_delivery_pending ON device_command_delivery(status, expires_at);

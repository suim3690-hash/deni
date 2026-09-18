-- 기존 기록의 원본 요청·이벤트 ID는 알 수 없으므로 소급 생성하지 않는다.
ALTER TABLE children ADD COLUMN registration_input_hash VARCHAR(64);
ALTER TABLE children ADD CONSTRAINT ck_children_registration_input_hash
    CHECK (registration_input_hash IS NULL OR registration_input_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE hazards ADD COLUMN source_event_id VARCHAR(100);
ALTER TABLE hazards ADD COLUMN detection_input_hash VARCHAR(64);
ALTER TABLE hazards ADD CONSTRAINT ck_hazards_detection_identity
    CHECK ((source_event_id IS NULL AND detection_input_hash IS NULL)
        OR (source_event_id IS NOT NULL AND detection_input_hash IS NOT NULL
            AND char_length(btrim(source_event_id)) BETWEEN 1 AND 100
            AND detection_input_hash ~ '^[0-9a-f]{64}$'));

CREATE UNIQUE INDEX uq_hazards_device_event ON hazards (device_id, source_event_id)
    WHERE source_event_id IS NOT NULL;

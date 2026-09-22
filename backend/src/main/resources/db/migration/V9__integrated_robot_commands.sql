ALTER TABLE operation_requests DROP CONSTRAINT ck_operation_requests_scope;
ALTER TABLE operation_requests ADD CONSTRAINT ck_operation_requests_scope CHECK (
    (kind IN ('PAUSE','RESUME','POWER_ON','POWER_OFF') AND hazard_id IS NULL AND status='REQUESTED')
    OR (kind IN ('DIRECT_REMOVAL_CHECK','RELOCATE') AND hazard_id IS NOT NULL AND status='UNKNOWN')
);
ALTER TABLE device_command_delivery
    ADD COLUMN result_operation_state VARCHAR(20),
    ADD COLUMN hazard_present BOOLEAN,
    ADD COLUMN result_payload TEXT;
-- A merged detection must retain its own receipt even after the hazard is resolved.
ALTER TABLE detection_events ADD COLUMN hazard_id UUID REFERENCES hazards(id);
ALTER TABLE robot_live_state ADD COLUMN power_enabled BOOLEAN, ADD COLUMN task_state VARCHAR(40);
UPDATE detection_events e SET hazard_id=h.id FROM hazards h
    WHERE h.device_id=e.device_id AND h.source_event_id=e.event_id::text;

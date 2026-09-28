ALTER TABLE hazards ADD COLUMN object_instance_id UUID;
ALTER TABLE detection_events ADD COLUMN object_instance_id UUID;
CREATE INDEX ix_hazards_object_instance ON hazards(device_id, child_id, object_instance_id, status);

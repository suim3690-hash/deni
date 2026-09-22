-- Change both ACTIVE and RESOLVED swallow hazards to the current child's growth stage.
-- This intentionally replaces historical risk snapshots under the agreed demo policy.
UPDATE hazards AS h
SET risk_level = CASE c.stage
        WHEN 'INFANT' THEN 'HIGH'
        WHEN 'TODDLER' THEN 'VERY_HIGH'
        WHEN 'ACTIVE_CHILD' THEN 'MEDIUM'
    END,
    updated_at = clock_timestamp(),
    version = h.version + 1
FROM children AS c
WHERE h.child_id = c.id
  AND h.object_type = 'SWALLOW'
  AND c.stage IN ('INFANT', 'TODDLER', 'ACTIVE_CHILD')
  AND h.risk_level IS DISTINCT FROM CASE c.stage
        WHEN 'INFANT' THEN 'HIGH'
        WHEN 'TODDLER' THEN 'VERY_HIGH'
        WHEN 'ACTIVE_CHILD' THEN 'MEDIUM'
    END;

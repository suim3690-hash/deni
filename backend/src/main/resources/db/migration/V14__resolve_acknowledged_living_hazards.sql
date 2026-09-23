-- Acknowledging a living-space hazard now means the user has completed it.
-- Migrate records acknowledged under the former policy so they leave active maps and alerts.
UPDATE hazards
SET status = 'RESOLVED',
    updated_at = clock_timestamp(),
    version = version + 1
WHERE object_type = 'LIVING'
  AND status = 'ACTIVE'
  AND acknowledged_at IS NOT NULL;

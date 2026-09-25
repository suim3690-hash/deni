package com.deni.backend.hazard;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Derive temporary handling from existing command receipts; no schema changes. */
@Component
public class TreatmentHistory {
    private final JdbcTemplate jdbc;
    public TreatmentHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Set<UUID> relocated(String deviceId, UUID childId) {
        return new HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT h.id FROM hazards h
            JOIN operation_requests r ON r.hazard_id=h.id AND r.device_id=h.device_id
            JOIN device_command_delivery d ON d.command_id=r.id
            WHERE h.status='ACTIVE' AND r.kind='RELOCATE' AND d.status='SUCCEEDED'
              AND d.completed_at >= h.detected_at
              AND (CAST(? AS varchar) IS NULL OR h.device_id=?)
              AND (CAST(? AS uuid) IS NULL OR h.child_id=?)
            """, UUID.class, deviceId, deviceId, childId, childId));
    }

    public boolean relocated(UUID hazardId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM hazards h
              JOIN operation_requests r ON r.hazard_id=h.id AND r.device_id=h.device_id
              JOIN device_command_delivery d ON d.command_id=r.id
              WHERE h.id=? AND h.status='ACTIVE' AND r.kind='RELOCATE'
                AND d.status='SUCCEEDED' AND d.completed_at>=h.detected_at)
            """, Boolean.class, hazardId));
    }

    /** A hazard of the same kind whose handling finished after this frame was captured. */
    public UUID handledAfter(String deviceId, UUID childId, String type, String label, OffsetDateTime capturedAt) {
        var rows = jdbc.queryForList("""
            SELECT h.id FROM hazards h WHERE h.device_id=? AND h.child_id=?
              AND h.object_name=? AND h.object_type=?
              AND ((h.status='RESOLVED' AND h.updated_at>=?) OR EXISTS (
                SELECT 1 FROM operation_requests r JOIN device_command_delivery d ON d.command_id=r.id
                WHERE r.hazard_id=h.id AND r.device_id=h.device_id
                  AND r.kind IN ('DIRECT_REMOVAL_CHECK','RELOCATE')
                  AND d.status='SUCCEEDED' AND d.completed_at>=?))
            ORDER BY h.updated_at DESC LIMIT 1
            """, UUID.class, deviceId, childId, label, type, capturedAt, capturedAt);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}

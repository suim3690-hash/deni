package com.deni.backend.operation;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 자동 이송: 토글이 켜진 기기에서 남은 삼킴 위험을 사람의 요청 없이 하나씩 이송한다.
 *
 * <p>요청은 수동 이송과 같은 {@link OperationService#requestRelocation} 경로로 만든다. 기기 연결,
 * 전원 ON, 정지 상태, 진행 중 요청 없음 같은 안전 조건이 그 안에 이미 있어 여기서 다시 판단하지
 * 않는다. 조건이 맞지 않으면 예외가 나고 다음 주기에 다시 본다.
 *
 * <p>무인으로 반복하므로 멈출 규칙을 둔다. 같은 위험이 {@code SKIP_AFTER}번 실패하면 그 건은
 * 건너뛰고, 한 기기에서 {@code DISABLE_AFTER}번 연속 실패하면 토글을 꺼서 사람을 기다린다.
 */
@Component
public class AutoRelocationDispatcher {
    /** 이송 순서. 목록에 없는 종류는 맨 뒤로 가며, 같은 순위면 오래된 것이 먼저다. */
    static final java.util.List<String> PRIORITY = java.util.List.of("배터리", "구슬", "동전", "주사위");
    static final int SKIP_AFTER = 2;
    static final int DISABLE_AFTER = 3;

    private final JdbcTemplate jdbc;
    private final OperationService operations;
    /** 기기 -> 방금 발행한 요청. 다음 주기에 그 결과를 보고 실패를 센다. */
    private final Map<String, UUID> issued = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Integer> hazardFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> deviceFailures = new java.util.concurrent.ConcurrentHashMap<>();

    public AutoRelocationDispatcher(JdbcTemplate jdbc, OperationService operations) {
        this.jdbc = jdbc; this.operations = operations;
    }

    @Scheduled(fixedDelay = 2000)
    public void dispatch() {
        for (String device : jdbc.queryForList("SELECT id FROM devices WHERE auto_relocation", String.class)) {
            try {
                if (!settle(device)) continue;
                UUID hazard = nextHazard(device);
                if (hazard == null) continue;
                issued.put(device, operations.requestRelocation(hazard, UUID.randomUUID()).actionId());
            } catch (Exception ex) {
                // 정지 상태가 아니거나 이미 처리 중인 것은 흔한 경우다. 다음 주기에 다시 본다.
                issued.remove(device);
            }
        }
    }

    /** 앞서 발행한 요청이 끝났는지 본다. 아직이면 false, 끝났으면 성공·실패를 센 뒤 true. */
    private boolean settle(String device) {
        UUID command = issued.get(device);
        if (command == null) return true;
        var rows = jdbc.queryForList(
            "SELECT d.status, r.hazard_id FROM device_command_delivery d"
            + " JOIN operation_requests r ON r.id=d.command_id WHERE d.command_id=?", command);
        if (rows.isEmpty()) { issued.remove(device); return true; }
        String status = (String) rows.getFirst().get("status");
        if ("QUEUED".equals(status) || "SENT".equals(status) || "DELIVERED".equals(status)) return false;
        issued.remove(device);
        UUID hazard = (UUID) rows.getFirst().get("hazard_id");
        if ("SUCCEEDED".equals(status)) {
            deviceFailures.remove(device);
            if (hazard != null) hazardFailures.remove(hazard);
            return true;
        }
        if (hazard != null) hazardFailures.merge(hazard, 1, Integer::sum);
        if (deviceFailures.merge(device, 1, Integer::sum) >= DISABLE_AFTER) {
            jdbc.update("UPDATE devices SET auto_relocation=false, updated_at=clock_timestamp(),"
                + " version=version+1 WHERE id=?", device);
            deviceFailures.remove(device);
            return false;
        }
        return true;
    }

    /** 우선순위가 가장 높은 미처리 삼킴 위험. 반복 실패한 건과 처리 중인 건은 뺀다. */
    private UUID nextHazard(String device) {
        var arguments = new java.util.ArrayList<Object>();
        arguments.add(device);
        var order = new StringBuilder("CASE h.object_name");
        for (int rank = 0; rank < PRIORITY.size(); rank++) {
            order.append(" WHEN ? THEN ").append(rank);
            arguments.add(PRIORITY.get(rank));
        }
        order.append(" ELSE ").append(PRIORITY.size()).append(" END");
        var candidates = jdbc.queryForList("""
            SELECT h.id FROM hazards h WHERE h.device_id=? AND h.status='ACTIVE' AND h.object_type='SWALLOW'
              AND NOT EXISTS (SELECT 1 FROM operation_requests r JOIN device_command_delivery d
                ON d.command_id=r.id WHERE r.hazard_id=h.id
                  AND d.status IN ('QUEUED','SENT','DELIVERED','UNKNOWN'))
            ORDER BY """ + order + ", h.detected_at", UUID.class, arguments.toArray());
        for (UUID hazard : candidates) {
            if (hazardFailures.getOrDefault(hazard, 0) < SKIP_AFTER) return hazard;
        }
        return null;
    }
}

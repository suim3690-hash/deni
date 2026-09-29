package com.deni.backend.device;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class DeviceMessageService {
    private final JdbcTemplate jdbc;
    private final DeviceService devices;
    @org.springframework.beans.factory.annotation.Autowired
    private com.deni.backend.common.IdempotencyGuard guard;
    public DeviceMessageService(JdbcTemplate jdbc, DeviceService devices) { this.jdbc=jdbc; this.devices=devices; }
    private static final int VISIBLE_LIMIT=64;
    private static final long ABSENT_MILLIS=5000;
    /** 처리가 실패로 끝난 위험을 자동 정리에서 잠시 빼 두는 시간. */
    private static final int FAILURE_GRACE_SECONDS=30;
    /** hazardId -> 마지막으로 로봇이 보고 있다고 확인한 시각. 보고가 끊기면 다시 0부터 센다. */
    private final Map<UUID,Long> lastSeen=new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 실시간 인식이 5초 넘게 못 본 위험은 사용자 확인 없이 종료한다.
     *
     * 로봇이 "해제했다"가 아니라 "지금 보인다"를 보내므로, 이번에 켜진 로봇이 모르는
     * 이전 실행의 위험도 정리된다. 탐지가 살아 있다는 보고가 없으면 아무것도 지우지 않고
     * 타이머만 다시 시작한다. 빈 목록이 곧 "전부 사라짐"은 아니기 때문이다.
     */
    private void retireUnseen(String id, JsonNode payload) {
        var instances=new java.util.HashSet<UUID>();
        if(payload.path("visibleObjectInstanceIds").isArray())
            for(JsonNode node:payload.path("visibleObjectInstanceIds")) instances.add(UUID.fromString(node.asText()));
        var names=new java.util.HashSet<String>();
        if(payload.path("visibleObjectLabels").isArray())
            for(JsonNode node:payload.path("visibleObjectLabels")) names.add(node.asText());
        if(instances.size()>VISIBLE_LIMIT || names.size()>VISIBLE_LIMIT) throw new IllegalArgumentException();
        var active=jdbc.queryForList("SELECT id,object_name,object_instance_id FROM hazards"
            +" WHERE device_id=? AND status='ACTIVE'",id);
        long now=System.currentTimeMillis();
        if(!payload.path("detectionLive").asBoolean(false)) {
            for(var row:active) lastSeen.put((UUID)row.get("id"),now);
            return;
        }
        var retire=new java.util.ArrayList<UUID>();
        var alive=new java.util.HashSet<UUID>();
        for(var row:active) {
            UUID hazard=(UUID)row.get("id");
            alive.add(hazard);
            UUID instance=(UUID)row.get("object_instance_id");
            // 개체 식별자가 있으면 그 개체로만 판단한다. 같은 종류의 다른 물체가 보인다고
            // 살려두면, 치운 물체가 영원히 목록에 남는다.
            boolean seen=instance!=null ? instances.contains(instance) : names.contains(row.get("object_name"));
            if(seen) lastSeen.put(hazard,now);
            else if(now-lastSeen.computeIfAbsent(hazard,key->now) >= ABSENT_MILLIS) retire.add(hazard);
        }
        lastSeen.keySet().retainAll(alive);
        if(retire.isEmpty()) return;
        guard.lock("device",id);
        var arguments=new java.util.ArrayList<Object>(); arguments.add(id); arguments.addAll(retire);
        arguments.add(FAILURE_GRACE_SECONDS);
        // 아직 결과가 오지 않은 처리 요청의 대상은 그 요청이 끝낸다. 방금 실패한 요청의 대상도
        // 잠시 남겨 둔다. 실패는 물체가 아직 그 자리에 있다는 뜻이고, 실패 직후는 로봇이 물러나
        // 있어 안 보이기 쉽다. 여기서 바로 지우면 화면이 "직접 치워 주세요"라고 안내하면서 그
        // 항목을 목록에서 없애 버린다.
        jdbc.update("UPDATE hazards h SET status='RESOLVED',updated_at=clock_timestamp(),version=h.version+1"
            +" WHERE h.device_id=? AND h.status='ACTIVE' AND h.id IN ("+placeholders(retire.size())+")"
            +" AND NOT EXISTS (SELECT 1 FROM operation_requests r JOIN device_command_delivery d"
            +" ON d.command_id=r.id WHERE r.hazard_id=h.id"
            +" AND (d.status IN ('QUEUED','SENT','DELIVERED','UNKNOWN')"
            +"      OR (d.status='FAILED' AND d.completed_at > clock_timestamp() - ? * interval '1 second')))",
            arguments.toArray());
        lastSeen.keySet().removeAll(retire);
    }

    private static String placeholders(int count) {
        return String.join(",",java.util.Collections.nCopies(count,"?"));
    }

    @Transactional public void receive(String id, String type, JsonNode payload) {
        if ("ROBOT_STATE".equals(type)) {
            String operation=payload.path("operationState").asText();
            String movement=payload.path("movementState").asText();
            if (!Set.of("RUNNING","PAUSED","RELOCATING","UNKNOWN").contains(operation)) throw new IllegalArgumentException();
            if (!Set.of("FORWARD","TURNING","BACKWARD","STOPPED","UNKNOWN").contains(movement)) throw new IllegalArgumentException();
            // robot_live_state 트리거도 미래 시각을 거부하므로 저장 전에 같은 기준으로 맞춘다.
            OffsetDateTime sampled=devices.alignReportTime(OffsetDateTime.parse(payload.path("sampledAt").asText()));
            Long duration=payload.path("movementDurationMs").isNull() || payload.path("movementDurationMs").isMissingNode()
                ? null : Long.valueOf(payload.path("movementDurationMs").asText());
            java.math.BigDecimal distance=payload.path("movementDistanceM").isNull() || payload.path("movementDistanceM").isMissingNode()
                ? null : new java.math.BigDecimal(payload.path("movementDistanceM").asText());
            Integer battery=payload.path("batteryPercent").isNull() || payload.path("batteryPercent").isMissingNode()
                ? null : Integer.valueOf(payload.path("batteryPercent").asText());
            Boolean power=payload.path("powerEnabled").isBoolean()?payload.path("powerEnabled").asBoolean():null;
            String task=payload.path("taskState").isTextual()?payload.path("taskState").asText():null;
            if(task!=null && task.length()>40) throw new IllegalArgumentException();
            retireUnseen(id,payload);
            devices.recordStatus(new DeviceService.StatusInput(id,"ONLINE",operation.equals("RELOCATING")?"UNKNOWN":operation,battery,sampled));
            // The database has its own clock. Clamp only future samples at write
            // time; old samples must retain their age for freshness checks.
            jdbc.update("""
                INSERT INTO robot_live_state(device_id,operation_state,movement_state,sampled_at,movement_duration_ms,movement_distance_m,power_enabled,task_state)
                VALUES (?,?,?,LEAST(CAST(? AS timestamptz),clock_timestamp()),?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET
                operation_state=EXCLUDED.operation_state,movement_state=EXCLUDED.movement_state,
                sampled_at=EXCLUDED.sampled_at,movement_duration_ms=EXCLUDED.movement_duration_ms,
                movement_distance_m=EXCLUDED.movement_distance_m,power_enabled=EXCLUDED.power_enabled,task_state=EXCLUDED.task_state
                """,id,operation,movement,sampled,duration,distance,power,task);
            return;
        }
        UUID command=UUID.fromString(payload.path("commandId").asText());
        if ("COMMAND_ACK".equals(type) && "DELIVERED".equals(payload.path("status").asText())) {
            int updated=jdbc.update("UPDATE device_command_delivery SET status='DELIVERED' WHERE command_id=? AND device_id=? AND status IN ('SENT','DELIVERED')",command,id);
            if(updated==0) throw new IllegalArgumentException();
            return;
        }
        if (!"COMMAND_RESULT".equals(type)) throw new IllegalArgumentException();
        String status=payload.path("status").asText();
        if (!Set.of("SUCCEEDED","FAILED").contains(status)) throw new IllegalArgumentException();
        OffsetDateTime completed=OffsetDateTime.parse(payload.path("completedAt").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(completed.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException();
        guard.lock("device",id);
        var rows=jdbc.queryForList("SELECT d.status,d.sent_at,d.completed_at,d.result_payload,r.kind,r.hazard_id FROM device_command_delivery d JOIN operation_requests r ON r.id=d.command_id WHERE d.command_id=? AND d.device_id=? FOR UPDATE OF d",command,id);
        if(rows.isEmpty()) throw new IllegalArgumentException();
        String kind=(String)rows.getFirst().get("kind");
        CommandResultPolicy.validate(kind,payload);
        String previous=(String)rows.getFirst().get("status");
        if(previous.equals(status)) {
            OffsetDateTime original=jdbc.queryForObject("SELECT completed_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command);
            if(!original.toInstant().equals(completed.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toInstant())) throw new IllegalArgumentException();
            if(rows.getFirst().get("result_payload")!=null && !rows.getFirst().get("result_payload").equals(payload.toString())) throw new IllegalArgumentException();
            return;
        }
        if(!Set.of("SENT","DELIVERED","UNKNOWN").contains(previous)) throw new IllegalArgumentException();
        OffsetDateTime sent=jdbc.queryForObject("SELECT sent_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command);
        if(completed.isBefore(sent)) throw new IllegalArgumentException();
        String error=payload.path("errorCode").isTextual()?payload.path("errorCode").asText():null;
        if(error!=null && error.length()>100) throw new IllegalArgumentException();
        String operation=payload.path("operationState").asText("UNKNOWN");
        Boolean present=payload.path("hazardPresent").isBoolean()?payload.path("hazardPresent").asBoolean():null;
        jdbc.update("UPDATE device_command_delivery SET status=?,completed_at=?,error_code=?,result_operation_state=?,hazard_present=?,result_payload=? WHERE command_id=?",status,completed,error,operation,present,payload.toString(),command);
        if(status.equals("SUCCEEDED") && rows.getFirst().get("hazard_id")!=null) {
            if(!payload.path("hazardId").asText().equals(rows.getFirst().get("hazard_id").toString())) throw new IllegalArgumentException();
            // 이송 성공은 임시 완료다. 물체는 안전 구역으로 옮겨졌을 뿐 치워지지 않았으므로 위험은 ACTIVE로 두고
            // 다음 백엔드 시작의 시연 정책이 마무리한다. 직접 제거만 그 자리에서 해결로 본다.
            String hazardSql=kind.equals("RELOCATE")
                ? "UPDATE hazards SET device_operation_state=?,updated_at=clock_timestamp(),version=version+1 WHERE id=? AND device_id=? AND status='ACTIVE'"
                : "UPDATE hazards SET status='RESOLVED',device_operation_state=?,updated_at=clock_timestamp(),version=version+1 WHERE id=? AND device_id=? AND status='ACTIVE'";
            jdbc.update(hazardSql,operation,rows.getFirst().get("hazard_id"),id);
        }
    }
}
